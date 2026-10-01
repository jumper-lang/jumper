package me.padej.jumper.script;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import java.util.List;

/** Registered via META-INF/services: new ScriptEngineManager().getEngineByName("jmp"). */
public final class JmpScriptEngineFactory implements ScriptEngineFactory {
    public static final String VERSION = "0.11";

    @Override public String getEngineName() { return "Jumper"; }
    @Override public String getEngineVersion() { return VERSION; }
    @Override public List<String> getExtensions() { return List.of("jmp"); }
    @Override public List<String> getMimeTypes() { return List.of("application/x-jmp", "text/x-jmp"); }
    @Override public List<String> getNames() { return List.of("jmp", "jumper", "Jumper"); }
    @Override public String getLanguageName() { return "Jumper"; }
    @Override public String getLanguageVersion() { return VERSION; }

    @Override
    public Object getParameter(String key) {
        return switch (key) {
            case ScriptEngine.ENGINE -> getEngineName();
            case ScriptEngine.ENGINE_VERSION -> getEngineVersion();
            case ScriptEngine.NAME -> "jmp";
            case ScriptEngine.LANGUAGE -> getLanguageName();
            case ScriptEngine.LANGUAGE_VERSION -> getLanguageVersion();
            case "THREADING" -> null; // the engine is not thread-safe for concurrent eval
            default -> null;
        };
    }

    @Override
    public String getMethodCallSyntax(String obj, String m, String... args) {
        return obj + "." + m + "(" + String.join(", ", args) + ")";
    }

    @Override
    public String getOutputStatement(String toDisplay) {
        return "println(" + toDisplay + ")";
    }

    @Override
    public String getProgram(String... statements) {
        StringBuilder sb = new StringBuilder();
        for (String s : statements) sb.append(s).append(";\n");
        return sb.toString();
    }

    @Override
    public ScriptEngine getScriptEngine() {
        return new JmpScriptEngine(this);
    }
}
