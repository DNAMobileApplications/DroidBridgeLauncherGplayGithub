package ca.dnamobile.droidbridgelauncher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

public final class StartInjectors {
    private StartInjectors() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        System.out.println("DroidBridge Agent: premain started");

        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    ClassLoader loader,
                    String className,
                    Class<?> classBeingRedefined,
                    ProtectionDomain protectionDomain,
                    byte[] classfileBuffer
            ) {
                if (!VeilImguiOverrideDisable.TARGET_INTERNAL_NAME.equals(className)) {
                    return null;
                }

                try {
                    byte[] patched = VeilImguiOverrideDisable.patch(classfileBuffer);
                    if (patched != null) {
                        System.out.println("DroidBridge Agent: patched "
                                + VeilImguiOverrideDisable.TARGET_INTERNAL_NAME
                                + "#setImGuiPath() for Android");
                        return patched;
                    }

                    System.out.println("DroidBridge Agent: target class found but setImGuiPath() was not patched");
                    return null;
                } catch (Throwable throwable) {
                    System.out.println("DroidBridge Agent: failed to patch VeilImGuiImpl: " + throwable);
                    throwable.printStackTrace(System.out);
                    return null;
                }
            }
        }, false);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        premain(agentArgs, instrumentation);
    }
}
