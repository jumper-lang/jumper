package me.padej.jumper.runtime;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Policy for script access to Java. Optional: without it a script sees everything, the way Lua or
 * Python see their standard library - the source is in plain view, and checking it is the job of
 * whoever runs it. A policy is for a host that runs other people's scripts automatically (mods,
 * rules, user configs): nobody is there to check, so the host says in one line what "everything"
 * means for it.
 *
 * <p><b>Whitelist.</b> Only what is listed is allowed; the most specific rule wins; {@code deny}
 * on a class swallows everything inside it. A package rule never opens the sandbox escape routes
 * ({@link #ESCAPES}: {@code Class}, {@code ClassLoader}, {@code Runtime}, {@code ProcessBuilder},
 * {@code System}, {@code Thread}, reflection, {@code invoke}) - those can be opened only explicitly
 * by class name, and that is visible in the policy file's diff. {@code getClass()} is closed until
 * {@code java.lang.Class} is explicitly allowed.
 *
 * <p>Objects the host itself put into the script ({@code engine.put}, {@code define}) are no
 * different from the rest: to call their methods, their class must be allowed. This is deliberate -
 * the host knows what it puts in and adds one line.
 *
 * <p>The policy file {@code *.jma} (JuMper Access) is an ordinary Jumper script with the
 * {@link me.padej.jumper.security.Policy} dictionary; the policy has no format of its own, the
 * language is the config language:
 * <pre>
 * // mods.jma
 * Policy.allowPackage("net.server.api");
 * Policy.allowClass("net.server.world.Entity")
 *       .denyMethod("setHealth");
 * Policy.allowPackage("java.lang");             // String, Math... - but not Class/Runtime/System/Thread
 * Policy.allowMethod("java.lang.System", "currentTimeMillis");
 * Policy.allowModules();
 * </pre>
 * The host sets the file path ({@link #load}); the CLI takes it from {@code -Djmp.access} or {@code JMP_ACCESS}.
 * The file itself is protected by filesystem permissions: whoever can write it has everything.
 *
 * <p>The checks sit at the two places through which a script reaches Java: {@link Interop#findClass}
 * (import, new, Foo.class, qualified names) and member selection ({@link Interop#candidates},
 * fields, properties, constructors) - including {@code invokedynamic}, which resolves the method
 * in the same place. The current policy is in a ThreadLocal, set on script entry
 * ({@code ScriptFunction.call}, the engine, interface proxies). Until a policy has been created
 * there are no checks at all.
 *
 * <p>A violation is a {@link ScriptSecurityException} (a {@link SecurityException}) thrown at linkage,
 * before the member runs; the script cannot catch it. Some routes are closed under every policy, whatever
 * its rules ({@link #hardDenied}): class loaders, and everything of {@code java.lang.Class} but
 * {@link #CLASS_SAFE}. A Class that reaches a script is a {@link JavaClass} ({@link Interop#wrapClass}).
 * A member rule applies to overrides and implementations too ({@link #memberAllowed}).
 */
public final class Access {
    /** Everything allowed (the default behavior). */
    public static final Access ALL = new Access(true);

    /** Classes no package rule opens - only an explicit class rule does. */
    static final String[] ESCAPES = {
            "java.lang.Class", "java.lang.ClassLoader", "java.lang.Runtime", "java.lang.ProcessBuilder",
            "java.lang.Process", "java.lang.ProcessHandle", "java.lang.System", "java.lang.Thread",
            "java.lang.ThreadGroup", "java.lang.Module", "java.lang.ModuleLayer", "java.lang.StackWalker",
            "java.lang.reflect.", "java.lang.invoke.", "java.lang.ref.", "sun.", "jdk.internal.", "jdk.",
            "java.security.", "javax.script.", "me.padej.jumper.",
            // thread spawners: a script must not outlive cancel() or run outside the policy's ThreadLocal
            "java.util.Timer", "java.util.concurrent.Executors", "java.util.concurrent.ForkJoinPool",
            "java.util.concurrent.ForkJoinTask", "java.util.concurrent.CompletableFuture",
            "java.util.concurrent.ThreadPoolExecutor", "java.util.concurrent.ScheduledThreadPoolExecutor",
            "java.util.concurrent.Executor", "java.util.concurrent.ExecutorService",
            // instantiates whatever provider classes the class path has
            "java.util.ServiceLoader",
            // native code and process/VM control reachable from a plain allowPackage("java.lang"):
            // FFI downcalls, the platform MBean server and all system properties, module internals
            "java.lang.foreign.", "java.lang.management.", "java.lang.module.",
            // reachable from a plain allowPackage("java.util"): file/socket log sinks, tool runners,
            // on-disk preferences, and java.util.Formatter(String) which opens a file with only a String arg
            "java.util.logging.", "java.util.spi.", "java.util.prefs.", "java.util.Formatter"
    };

    /**
     * What a {@code java.lang.Class} value can be asked under a policy, whatever the rules say: its
     * identity and shape, nothing that leads further - no loader, no forName, no Method/Field/Constructor
     * objects, no module, no resources, no instances. A whitelist, so a method a future JDK adds to Class
     * is closed until it is listed here. See {@link #hardDenied}.
     */
    static final java.util.Set<String> CLASS_SAFE = java.util.Set.of(
            "getName", "getSimpleName", "getTypeName", "getCanonicalName", "getPackageName", "descriptorString",
            "isInstance", "isAssignableFrom", "isInterface", "isArray", "isPrimitive", "isEnum", "isRecord",
            "isAnnotation", "isSealed", "isSynthetic", "isAnonymousClass", "isLocalClass", "isMemberClass",
            "isHidden", "getModifiers", "cast", "getEnumConstants", "toString", "hashCode", "equals");

    private enum Kind { PACKAGE, CLASS, MEMBER }
    private record Rule(boolean allow, Kind kind, String target, String member) {}

    private final boolean all;
    private final List<Rule> rules = new ArrayList<>();
    private boolean modules;
    private int maxTableSize = Integer.MAX_VALUE;
    private final Map<Class<?>, Boolean> classCache = new HashMap<>();

    private Access(boolean all) { this.all = all; }

    /** Empty policy: nothing is allowed until rules are added. */
    public static Access none() { return new Access(false); }

    /** Read a {@code *.jma} policy file (a Jumper script with the {@code Policy} dictionary). */
    public static Access load(java.nio.file.Path file) throws java.io.IOException {
        return me.padej.jumper.security.Policy.build(
                java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8), file.toString());
    }

    /** Policy from text in {@code *.jma} format. */
    public static Access parse(String jmaSource) {
        return me.padej.jumper.security.Policy.build(jmaSource, "<text>");
    }

    // ---------- building from code ----------

    public Access allowPackage(String pkg) { rules.add(new Rule(true, Kind.PACKAGE, pkg, null)); return this; }
    public Access denyPackage(String pkg) { rules.add(new Rule(false, Kind.PACKAGE, pkg, null)); return this; }
    public Access allowClass(Class<?> c) { return allowClass(c.getName()); }
    public Access allowClass(String name) { rules.add(new Rule(true, Kind.CLASS, name, null)); return this; }
    public Access denyClass(String name) { rules.add(new Rule(false, Kind.CLASS, name, null)); return this; }
    public Access allowMember(String cls, String member) { rules.add(new Rule(true, Kind.MEMBER, cls, member)); return this; }
    public Access denyMember(String cls, String member) { rules.add(new Rule(false, Kind.MEMBER, cls, member)); return this; }
    public Access allowModules(boolean on) { modules = on; return this; }

    /**
     * Upper bound on the number of elements in one script table or array; growing past it throws
     * {@code JmpError("Table limit exceeded")}. A cheap guard against a runaway {@code while (true) a.add(x)}
     * - it is checked only when a container grows (amortized), not on every write. It is not a memory
     * budget: strings, Java collections and the number of tables are not counted; the real boundary of
     * a sandbox is still the JVM heap ({@code -Xmx}) of the process that runs the scripts.
     */
    public Access maxTableSize(int n) { maxTableSize = n; return this; }

    public int maxTableSize() { return maxTableSize; }

    /**
     * The table limit of the policy in effect, or {@code Integer.MAX_VALUE}. Containers ask for it
     * only when they grow (arrays: at a capacity doubling; tables: at a dictionary insert), so the
     * ThreadLocal read is off the hot path of reads and in-place writes.
     */
    public static int tableLimit() {
        if (!active) return Integer.MAX_VALUE;
        Access a = CURRENT.get();
        return a == null ? Integer.MAX_VALUE : a.maxTableSize;
    }

    public static JmpError tableLimitExceeded(int limit) {
        return new JmpError("Table limit exceeded: " + limit + " elements (access policy)");
    }

    public boolean modulesAllowed() { return all || modules; }

    // ---------- checks ----------

    /** Whether the script may see the class: import, new, Foo.class, static access, method calls on its instances. */
    public boolean classAllowed(Class<?> c) {
        if (all) return true;
        synchronized (classCache) {
            Boolean b = classCache.get(c);
            if (b != null) return b;
            b = compute(c);
            classCache.put(c, b);
            return b;
        }
    }

    private boolean compute(Class<?> c) {
        while (c.isArray()) c = c.getComponentType();
        if (c.isPrimitive()) return true;
        if (ClassLoader.class.isAssignableFrom(c)) return false;   // hard: no rule opens a class loader
        String name = c.getName();
        // an explicit class rule is the strongest
        Boolean cls = null;
        for (Rule r : rules) if (r.kind == Kind.CLASS && r.target.equals(name)) cls = cls == null ? r.allow : (cls && r.allow);
        if (cls != null) return cls;
        if (isEscape(name)) return false;
        // the longest matching package
        String best = null; boolean allow = false;
        for (Rule r : rules) {
            if (r.kind != Kind.PACKAGE) continue;
            if (!name.startsWith(r.target + ".")) continue;
            if (best == null || r.target.length() > best.length() || (r.target.length() == best.length() && !r.allow)) { best = r.target; allow = r.allow; }
        }
        return best != null && allow;
    }

    private static boolean isEscape(String name) {
        for (String e : ESCAPES) if (e.endsWith(".") ? name.startsWith(e) : name.equals(e)) return true;
        return false;
    }

    /**
     * Whether the class is visible by name (import, new, qualified name): allowed as a whole or
     * through at least one member - `allow method java.lang.System currentTimeMillis` makes the
     * name `System` reachable while everything else in it stays closed.
     */
    public boolean visible(Class<?> c) {
        if (all || classAllowed(c)) return true;
        if (ClassLoader.class.isAssignableFrom(c)) return false;
        String name = c.getName();
        for (Rule r : rules) if (r.allow && r.kind == Kind.MEMBER && r.target.equals(name)) return true;
        return false;
    }

    /**
     * Whether member {@code member}, declared in {@code declaring}, may be accessed on an object
     * of class {@code runtime} (the same class for statics). A member rule beats a class rule;
     * if the class is not allowed the member is unreachable even if allowed by name on another.
     */
    public boolean memberAllowed(Class<?> declaring, Class<?> runtime, String member) {
        if (all) return true;
        if (hardDenied(declaring, runtime, member)) return false;
        // the most specific rule along the receiver's supertypes: `deny method Entity setHealth` also
        // closes Zombie.setHealth that overrides it, and a rule on an interface closes its implementations
        Boolean m = inheritedRule(runtime, member);
        if (m == null && runtime != declaring) m = inheritedRule(declaring, member);
        if (m != null) return m;
        if (member.equals("getClass") && !classAllowed(Class.class)) return false;   // the only route into reflection from any object
        return classAllowed(declaring) || classAllowed(runtime);
    }

    /**
     * Closed under every policy, no rule opens it - the routes out of the sandbox that a class rule
     * could otherwise open by accident ({@code allowPackage("java")}, {@code allowClass("java.lang.Class")}
     * for getName()):
     * <ul>
     *   <li>any member of a {@link ClassLoader} (of any subclass: URLClassLoader too), static or not;</li>
     *   <li>any member of {@code java.lang.Class} outside {@link #CLASS_SAFE}: {@code forName},
     *   {@code getClassLoader}, {@code getMethods}, {@code getDeclaredField}, {@code getModule},
     *   {@code getProtectionDomain}, {@code getResource}, {@code newInstance}...</li>
     * </ul>
     * Methods and fields whose type is a ClassLoader ({@code Thread.getContextClassLoader},
     * {@code Module.getClassLoader}, a host API) are closed in {@link #filter} and {@link #fieldAllowed}.
     */
    static boolean hardDenied(Class<?> declaring, Class<?> runtime, String member) {
        if (ClassLoader.class.isAssignableFrom(declaring) || ClassLoader.class.isAssignableFrom(runtime)) return true;
        return (declaring == Class.class || runtime == Class.class) && !CLASS_SAFE.contains(member);
    }

    /** A Java field read or written by a script: {@link #memberAllowed}, and never one that holds a class loader. */
    public boolean fieldAllowed(java.lang.reflect.Field f, Class<?> runtime) {
        if (all) return true;
        if (ClassLoader.class.isAssignableFrom(f.getType())) return false;
        return memberAllowed(f.getDeclaringClass(), runtime, f.getName());
    }

    private record MemberKey(Class<?> cls, String member) {}
    private final Map<MemberKey, Object> ruleCache = new HashMap<>();
    private static final Object NO_RULE = new Object();

    /**
     * The member rule for c or, if it has none, for the nearest supertypes that have one (breadth-first:
     * the class, then its superclass and interfaces, and so on); a deny wins within one level.
     */
    private Boolean inheritedRule(Class<?> c, String member) {
        MemberKey k = new MemberKey(c, member);
        synchronized (ruleCache) {
            Object r = ruleCache.get(k);
            if (r != null) return r == NO_RULE ? null : (Boolean) r;
        }
        Boolean out = null;
        List<Class<?>> level = List.of(c);
        java.util.Set<Class<?>> seen = new java.util.HashSet<>();
        while (!level.isEmpty() && out == null) {
            List<Class<?>> next = new ArrayList<>();
            for (Class<?> x : level) {
                if (!seen.add(x)) continue;
                Boolean r = memberRule(x, member);
                if (r != null) out = out == null ? r : (out && r);
                if (x.getSuperclass() != null) next.add(x.getSuperclass());
                next.addAll(List.of(x.getInterfaces()));
            }
            level = next;
        }
        synchronized (ruleCache) {
            ruleCache.put(k, out == null ? NO_RULE : out);
        }
        return out;
    }

    private Boolean memberRule(Class<?> c, String member) {
        String name = c.getName();
        Boolean out = null;
        for (Rule r : rules) if (r.kind == Kind.MEMBER && r.member.equals(member) && r.target.equals(name)) out = out == null ? r.allow : (out && r.allow);
        return out;
    }

    /** Keep only the allowed candidates (statics are judged by their declaring class). */
    public Method[] filter(Method[] cands, Class<?> runtime) {
        if (all || cands.length == 0) return cands;
        List<Method> out = new ArrayList<>(cands.length);
        for (Method m : cands) {
            Class<?> rt = Modifier.isStatic(m.getModifiers()) ? m.getDeclaringClass() : runtime;
            if (ClassLoader.class.isAssignableFrom(m.getReturnType())) continue;   // hard: no route to a loader
            if (memberAllowed(m.getDeclaringClass(), rt, m.getName())) out.add(m);
        }
        return out.size() == cands.length ? cands : out.toArray(new Method[0]);
    }

    public ScriptSecurityException denied(Class<?> c) {
        return new ScriptSecurityException("Access denied: " + c.getName());
    }

    public ScriptSecurityException denied(Class<?> c, String member) {
        return new ScriptSecurityException("Access denied: " + c.getName() + "." + member);
    }

    // ---------- the thread's current policy ----------

    private static final ThreadLocal<Access> CURRENT = new ThreadLocal<>();
    /** Whether a policy has ever been set: until then the checks cost nothing. */
    private static volatile boolean active;

    /** The policy in effect for the code currently running on this thread. */
    public static Access current() {
        if (!active) return ALL;
        Access a = CURRENT.get();
        return a == null ? ALL : a;
    }

    /** Set the policy for the duration of a call; returns the previous one for {@link #exit}. */
    public static Access enter(Access a) {
        if (a == null) return null;
        if (a != ALL) active = true;
        Access prev = CURRENT.get();
        CURRENT.set(a);
        return prev;
    }

    public static void exit(Access prev) {
        if (prev == null) CURRENT.remove(); else CURRENT.set(prev);
    }

    @Override
    public String toString() {
        return all ? "Access.ALL" : "Access(" + rules.size() + " rules)";
    }
}
