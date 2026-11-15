import net.minecraft.launchwrapper.IClassTransformer;

import java.nio.file.Files;
import java.nio.file.Paths;

/** Records the final transformed bytes of each Minecraft class for the harness. */
public class Recorder implements IClassTransformer {
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes != null && transformedName != null && transformedName.startsWith("net.minecraft.")) {
            try {
                Files.write(Paths.get("/tmp/dusk-mixincheck/out", transformedName.replace('.', '_') + ".class"), bytes);
            } catch (Exception ignored) {
            }
        }
        return bytes;
    }
}
