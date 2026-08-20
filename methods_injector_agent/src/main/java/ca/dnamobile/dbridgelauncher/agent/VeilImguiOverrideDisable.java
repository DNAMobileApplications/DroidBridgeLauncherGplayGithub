package ca.dnamobile.dbridgelauncher.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class VeilImguiOverrideDisable {
    public static final String TARGET_INTERNAL_NAME = "foundry/veil/impl/client/imgui/VeilImGuiImpl";
    private static final String TARGET_METHOD_NAME = "setImGuiPath";
    private static final String TARGET_METHOD_DESC = "()V";

    private VeilImguiOverrideDisable() {
    }

    public static byte[] patch(byte[] originalBytes) {
        ClassReader reader = new ClassReader(originalBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        boolean patched = false;

        for (MethodNode method : classNode.methods) {
            if (!TARGET_METHOD_NAME.equals(method.name) || !TARGET_METHOD_DESC.equals(method.desc)) {
                continue;
            }

            InsnList replacement = new InsnList();
            replacement.add(new InsnNode(Opcodes.RETURN));

            method.instructions.clear();
            method.instructions.add(replacement);

            if (method.tryCatchBlocks != null) {
                method.tryCatchBlocks.clear();
            }
            if (method.localVariables != null) {
                method.localVariables.clear();
            }

            // setImGuiPath() is void and now only returns.
            // Keep maxLocals from the original method to avoid touching static/instance assumptions.
            method.maxStack = 0;
            patched = true;
        }

        if (!patched) {
            System.out.println("DroidBridge Agent: VeilImGuiImpl found, but setImGuiPath()V was not found");
            for (MethodNode method : classNode.methods) {
                System.out.println("DroidBridge Agent: Veil method: " + method.name + method.desc);
            }
            return null;
        }

        // IMPORTANT:
        // Do not use ClassWriter.COMPUTE_FRAMES here.
        // Fabric/Knot loads Veil through KnotClassLoader, while ASM's default
        // COMPUTE_FRAMES lookup tries the system/app class loader and crashes with:
        // TypeNotPresentException: foundry/veil/impl/client/imgui/VeilImGuiImpl not present.
        ClassWriter writer = new ClassWriter(reader, 0);
        classNode.accept(writer);
        return writer.toByteArray();
    }
}
