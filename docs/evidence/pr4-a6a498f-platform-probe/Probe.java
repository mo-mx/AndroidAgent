import java.io.*;
import java.nio.file.*;

public class Probe {
  public static void main(String[] a) throws Exception {
    File dir = Files.createTempDirectory("probe").toFile();
    File target = new File(dir, "APP_MEMORY.md");
    Files.write(target.toPath(), "old".getBytes());

    // (1) exactly what MemoryStore.atomicWrite does: temp file + renameTo over an existing target
    File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
    Files.write(tmp.toPath(), "new".getBytes());
    boolean r1 = tmp.renameTo(target);
    System.out.println("renameTo over EXISTING target = " + r1
        + " ; target content = " + new String(Files.readAllBytes(target.toPath()))
        + " ; tmp still exists = " + tmp.exists());

    // (2) the same call when the target does not exist
    Files.write(tmp.toPath(), "new".getBytes());
    target.delete();
    boolean r2 = tmp.renameTo(target);
    System.out.println("renameTo over MISSING target  = " + r2
        + " ; target content = " + (target.exists() ? new String(Files.readAllBytes(target.toPath())) : "<none>"));

    // (3) the JDK alternative that replaces an existing file
    Files.write(tmp.toPath(), "v3".getBytes());
    try {
      Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
      System.out.println("Files.move(REPLACE_EXISTING) = true ; content = "
          + new String(Files.readAllBytes(target.toPath())));
    } catch (Exception e) {
      System.out.println("Files.move failed: " + e);
    }

    // (4) path separator as AgentSkillCatalogTest sees it
    File skill = new File(new File(dir, "my-skill"), "SKILL.md");
    System.out.println("getPath() = " + skill.getPath());
    System.out.println("endsWith(\"my-skill/SKILL.md\") = " + skill.getPath().endsWith("my-skill/SKILL.md")
        + " ; File.separator = '" + File.separator + "' ; os.name = " + System.getProperty("os.name"));
    System.out.println("java.version = " + System.getProperty("java.version"));
  }
}
