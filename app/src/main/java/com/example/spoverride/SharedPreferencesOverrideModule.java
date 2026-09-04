package com.example.spoverride;

import android.app.Application;
import android.app.Instrumentation;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Member;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class SharedPreferencesOverrideModule implements IXposedHookLoadPackage {

    private static final String TAG = "SPOverride";

    private static final String ASSET_FILE = "sp_rules.json";
    private static final String SECTION_GET_OVERRIDES = "get_overrides";
    private static final String SECTION_PUT_OVERRIDES = "put_overrides";

    private static final String FRAMEWORK_READ_CLASS = "android.app.SharedPreferencesImpl";
    private static final String FRAMEWORK_WRITE_CLASS = "android.app.SharedPreferencesImpl$EditorImpl";

    private static final String TYPE_STRING = "String";
    private static final String TYPE_BOOLEAN = "Boolean";
    private static final String TYPE_INTEGER = "Integer";
    private static final String TYPE_LONG = "Long";
    private static final String TYPE_FLOAT = "Float";

    private static final String[] READ_METHODS =
            {"getString", "getBoolean", "getInt", "getLong", "getFloat"};
    private static final String[] WRITE_METHODS =
            {"putString", "putBoolean", "putInt", "putLong", "putFloat"};

    private static String logFilePath = null;

    private JSONObject getOverrides;
    private JSONObject putOverrides;
    private boolean rulesReady;
    private boolean hooksInstalled;

    private static void fileLog(String message) {
        String fullMessage = TAG + ": " + message;
        XposedBridge.log(fullMessage);

        if (logFilePath != null) {
            try {
                File logFile = new File(logFilePath);
                File dir = logFile.getParentFile();
                if (dir != null && !dir.exists()) {
                    dir.mkdirs();
                }
                FileWriter fw = new FileWriter(logFile, true);
                BufferedWriter bw = new BufferedWriter(fw);
                String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                bw.write(timestamp + " " + fullMessage);
                bw.newLine();
                bw.close();
            } catch (Throwable ignore) {
                // Fail silently to prevent crashing the target app if storage is inaccessible
            }
        }
    }

    private final XC_MethodHook readHook = new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            if (!rulesReady || getOverrides == null || getOverrides.length() == 0) {
                return;
            }
            if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof String)) {
                return;
            }
            String key = (String) param.args[0];
            Object ruleValue = getOverrides.opt(key);
            if (ruleValue == null) {
                return;
            }
            String expectedType = resolveExpectedType(param.method, param.getResult());
            Object typed = coerceRuleValue(ruleValue, expectedType);
            if (typed == null) {
                fileLog("GET ignored [" + key + "] (rule value '"
                        + ruleValue + "' is not a valid " + expectedType + "); keeping original result.");
                return;
            }
            fileLog("GET [" + key + "] " + expectedType
                    + " old=" + describeValue(param.getResult())
                    + " -> new=" + describeValue(typed)
                    + " (" + param.method.getDeclaringClass().getSimpleName() + "." + param.method.getName() + ")");
            param.setResult(typed);
        }
    };

    private final XC_MethodHook writeHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            if (!rulesReady || putOverrides == null || putOverrides.length() == 0) {
                return;
            }
            if (param.args == null || param.args.length < 2 || !(param.args[0] instanceof String)) {
                return;
            }
            String key = (String) param.args[0];
            Object ruleValue = putOverrides.opt(key);
            if (ruleValue == null) {
                return;
            }
            String expectedType = resolveExpectedType(param.method, param.args[1]);
            Object typed = coerceRuleValue(ruleValue, expectedType);
            if (typed == null) {
                fileLog("PUT ignored [" + key + "] (rule value '"
                        + ruleValue + "' is not a valid " + expectedType + "); keeping original value.");
                return;
            }
            fileLog("PUT [" + key + "] " + expectedType
                    + " old=" + describeValue(param.args[1])
                    + " -> new=" + describeValue(typed)
                    + " (" + param.method.getDeclaringClass().getSimpleName() + "." + param.method.getName() + ")");
            param.args[1] = typed;
        }
    };

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) throws Throwable {
        if (lpparam.packageName == null || lpparam.processName == null) {
            return;
        }
        boolean primaryProcess = lpparam.packageName.equals(lpparam.processName)
                || lpparam.processName.startsWith(lpparam.packageName + ":");
        if (!primaryProcess) {
            return;
        }

        // Initialize target-specific log path in the zero-permission media directory
        logFilePath = "/sdcard/Android/media/" + lpparam.packageName + "/sp_override_logs.txt";
        fileLog("handleLoadPackage pkg=" + lpparam.packageName + " process=" + lpparam.processName);

        hookApplicationStartup(lpparam);
    }

    private void hookApplicationStartup(final LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!rulesReady && param.thisObject instanceof Application) {
                        initialize((Application) param.thisObject);
                    }
                }
            });
        } catch (Throwable t) {
            fileLog("Could not hook Application.onCreate: " + Log.getStackTraceString(t));
        }

        try {
            XposedHelpers.findAndHookMethod(Instrumentation.class,
                    "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (!rulesReady && param.args != null
                                    && param.args.length > 0 && param.args[0] instanceof Application) {
                                initialize((Application) param.args[0]);
                            }
                        }
                    });
        } catch (Throwable t) {
            fileLog("Could not hook Instrumentation.callApplicationOnCreate: " + Log.getStackTraceString(t));
        }
    }

    private synchronized void initialize(Application app) {
        if (rulesReady) {
            return;
        }
        rulesReady = true;
        try {
            loadRules(app);
        } catch (Throwable t) {
            fileLog("Overrides disabled: could not load 'assets/" + ASSET_FILE 
                    + "' from the application's asset manager. " + Log.getStackTraceString(t));
            return;
        }
        installFrameworkHooks(app);
    }

    private void loadRules(Application app) throws IOException, org.json.JSONException {
        String packageName = app.getPackageName();
        java.io.File ruleFile = new java.io.File("/sdcard/Android/media/" + packageName + "/sp_rules.json");
    
        if (!ruleFile.exists()) {
            throw new IOException("External rule file not found at: " + ruleFile.getAbsolutePath());
        }
    
        StringBuilder sb = new StringBuilder(4096);
        try (BufferedReader reader = new BufferedReader(new java.io.FileReader(ruleFile))) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
        }
        
        if (sb.length() == 0) {
            throw new IOException("Rule file '" + ruleFile.getAbsolutePath() + "' is empty");
        }
        
        JSONObject root = new JSONObject(sb.toString());
        getOverrides = root.optJSONObject(SECTION_GET_OVERRIDES);
        putOverrides = root.optJSONObject(SECTION_PUT_OVERRIDES);
        
        XposedBridge.log(TAG + " Rules loaded from " + ruleFile.getAbsolutePath()
                + " -> get_overrides=" + sectionSize(getOverrides)
                + ", put_overrides=" + sectionSize(putOverrides));
    }


    private void installFrameworkHooks(Application app) {
        if (hooksInstalled) {
            return;
        }
        ClassLoader cl = app.getClassLoader();
        try {
            Class<?> readClass = XposedHelpers.findClass(FRAMEWORK_READ_CLASS, cl);
            Class<?> writeClass = XposedHelpers.findClass(FRAMEWORK_WRITE_CLASS, cl);
            for (String methodName : READ_METHODS) {
                XposedBridge.hookAllMethods(readClass, methodName, readHook);
            }
            for (String methodName : WRITE_METHODS) {
                XposedBridge.hookAllMethods(writeClass, methodName, writeHook);
            }
            hooksInstalled = true;
            fileLog("Hooks installed on " + FRAMEWORK_READ_CLASS + " and " + FRAMEWORK_WRITE_CLASS + ".");
        } catch (Throwable t) {
            fileLog("Could not install framework hooks: " + Log.getStackTraceString(t));
        }
    }

    private static int sectionSize(JSONObject section) {
        return section == null ? 0 : section.length();
    }

    private static String resolveExpectedType(Member member, Object runtimeSample) {
        String type = primitiveTypeOf(runtimeSample);
        if (type != null) {
            return type;
        }
        if (member == null) {
            return null;
        }
        return methodNameToType(member.getName());
    }

    private static String methodNameToType(String methodName) {
        switch (methodName) {
            case "getString":
            case "putString":
                return TYPE_STRING;
            case "getBoolean":
            case "putBoolean":
                return TYPE_BOOLEAN;
            case "getInt":
            case "putInt":
                return TYPE_INTEGER;
            case "getLong":
            case "putLong":
                return TYPE_LONG;
            case "getFloat":
            case "putFloat":
                return TYPE_FLOAT;
            default:
                return null;
        }
    }

    private static String primitiveTypeOf(Object value) {
        if (value instanceof String) {
            return TYPE_STRING;
        }
        if (value instanceof Boolean) {
            return TYPE_BOOLEAN;
        }
        if (value instanceof Integer) {
            return TYPE_INTEGER;
        }
        if (value instanceof Long) {
            return TYPE_LONG;
        }
        if (value instanceof Float) {
            return TYPE_FLOAT;
        }
        return null;
    }

    private static Object coerceRuleValue(Object ruleValue, String expectedType) {
        if (ruleValue == null || JSONObject.NULL.equals(ruleValue) || expectedType == null) {
            return null;
        }
        try {
            String raw;
            String targetType = expectedType;
            if (ruleValue instanceof JSONObject) {
                JSONObject descriptor = (JSONObject) ruleValue;
                String declaredType = descriptor.optString("type", null);
                Object declaredValue = descriptor.opt("value");
                if (declaredType == null || declaredValue == null || JSONObject.NULL.equals(declaredValue)) {
                    fileLog("Invalid typed rule descriptor: " + descriptor);
                    return null;
                }
                targetType = declaredType;
                raw = declaredValue.toString();
            } else {
                raw = ruleValue.toString();
            }
            return convertPrimitive(raw.trim(), targetType);
        } catch (Throwable t) {
            fileLog("Could not coerce rule value '" + ruleValue + "' to " + expectedType + ": " + Log.getStackTraceString(t));
            return null;
        }
    }

    private static Object convertPrimitive(String raw, String type) {
        switch (type) {
            case TYPE_STRING:
                return raw;
            case TYPE_BOOLEAN:
                if ("true".equalsIgnoreCase(raw)) {
                    return Boolean.TRUE;
                }
                if ("false".equalsIgnoreCase(raw)) {
                    return Boolean.FALSE;
                }
                return null;
            case TYPE_INTEGER:
                return Integer.valueOf(raw);
            case TYPE_LONG:
                return Long.valueOf(raw);
            case TYPE_FLOAT:
                return Float.valueOf(raw);
            default:
                fileLog("Unsupported override type '" + type + "' (supported: "
                        + TYPE_STRING + ", " + TYPE_BOOLEAN + ", " + TYPE_INTEGER + ", "
                        + TYPE_LONG + ", " + TYPE_FLOAT + ").");
                return null;
        }
    }

    private static String describeValue(Object value) {
        if (value == null) {
            return "null";
        }
        return "'" + value + "' (" + value.getClass().getSimpleName() + ")";
    }
}
