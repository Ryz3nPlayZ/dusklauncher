# mixincheck — apply every mixin outside the game

Loads the real Minecraft classes through launchwrapper + Mixin 0.7.11 and
applies `mixins.duskclient.json` to them, exactly like the game does at
startup. A wrong handler signature fails here the same way it crashes the
game — run this before ever telling anyone a mixin change is fixed.

    # after ./gradlew build, from client-mod-189/:
    mkdir -p /tmp/dusk-mixincheck/{cp,out,classes,work/rt}
    Z8=/Users/zemuliu/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
    LOOM=~/.gradle/caches/essential-loom/1.8.9/de.oceanlabs.mcp.mcp_stable.1_8_9.22-1.8.9-forge-1.8.9-11.15.1.2318-1.8.9

    # dev-form classes under test (space 1), mixin runtime (space 2)
    ln -sfn "$PWD/build/classes/java/main" /tmp/dusk-mixincheck/cp/00-classes
    # the harness's Recorder transformer must be visible to the LaunchClassLoader
    ln -sfn /tmp/dusk-mixincheck/classes /tmp/dusk-mixincheck/cp/01-harness
    (cd /tmp/dusk-mixincheck/work/rt && unzip -q -o "$PWD/build/libs/duskclient-1.8.9-0.1.0.jar" \
      && rm org/spongepowered/asm/launch/platform/MixinPlatformAgentFML.class \
      && zip -q -r -X /tmp/dusk-mixincheck/cp/02-mixinrt.jar org META-INF)
    # space 3+: mapped MC + Forge, then the launchwrapper-era libs
    ln -sfn $LOOM/minecraft-mapped.jar /tmp/dusk-mixincheck/cp/10-minecraft.jar
    ln -sfn $LOOM/forge/forge-mapped.jar /tmp/dusk-mixincheck/cp/11-forge.jar
    ln -sfn ~/.gradle/caches/modules-2/files-2.1/net.minecraft/launchwrapper/1.12/111e7bea9c968cdb3d06ef4632bf7ff0824d0f36/launchwrapper-1.12.jar /tmp/dusk-mixincheck/cp/12-launchwrapper.jar
    ln -sfn ~/.gradle/caches/modules-2/files-2.1/com.google.guava/guava/17.0/9c6ef172e8de35fd8d4d8783e4821e57cdef7445/guava-17.0.jar /tmp/dusk-mixincheck/cp/13-guava.jar
    ln -sfn ~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.2.4/a60a5e993c98c864010053cb901b7eab25306568/gson-2.2.4.jar /tmp/dusk-mixincheck/cp/14-gson.jar
    ln -sfn $(find ~/.gradle/caches/modules-2/files-2.1/org.apache.logging.log4j/log4j-api -name 'log4j-api-2.0-beta9.jar' | head -1) /tmp/dusk-mixincheck/cp/15-log4j-api.jar
    ln -sfn ~/.gradle/caches/modules-2/files-2.1/org.apache.logging.log4j/log4j-core/2.0-beta9/678861ba1b2e1fccb594bb0ca03114bb05da9695/log4j-core-2.0-beta9.jar /tmp/dusk-mixincheck/cp/16-log4j-core.jar
    ln -sfn ~/.gradle/caches/modules-2/files-2.1/net.sf.jopt-simple/jopt-simple/5.0.4/4fdac2fbe92dfad86aa6e9301736f6b4342a3f5c/jopt-simple-5.0.4.jar /tmp/dusk-mixincheck/cp/17-jopt.jar
    ln -sfn ~/.gradle/caches/modules-2/files-2.1/commons-io/commons-io/2.4/b1b6ea3b7e4aa4f492509a4952029cd8e48019ad/commons-io-2.4.jar /tmp/dusk-mixincheck/cp/18-commons-io.jar
    # redirects of LWJGL calls (the Retina mouse) need the class loadable
    ln -sfn ~/.gradle/caches/modules-2/files-2.1/org.lwjgl.lwjgl/lwjgl/2.9.2-nightly-20140822/7707204c9ffa5d91662de95f0a224e2f721b22af/lwjgl-2.9.2-nightly-20140822.jar /tmp/dusk-mixincheck/cp/19-lwjgl.jar

    $Z8/bin/javac -proc:none -cp "/tmp/dusk-mixincheck/cp/02-mixinrt.jar:/tmp/dusk-mixincheck/cp/12-launchwrapper.jar" \
      -d /tmp/dusk-mixincheck/classes tools/mixincheck/Harness.java tools/mixincheck/Recorder.java \
      tools/mixincheck/fmlstub/net/minecraftforge/fml/common/launcher/FMLTweaker.java
    $Z8/bin/java -Dmixin.env.disableRefMap=true \
      -cp "/tmp/dusk-mixincheck/classes:/tmp/dusk-mixincheck/cp/00-classes:/tmp/dusk-mixincheck/cp/02-mixinrt.jar:/tmp/dusk-mixincheck/cp/10-minecraft.jar:/tmp/dusk-mixincheck/cp/11-forge.jar:/tmp/dusk-mixincheck/cp/12-launchwrapper.jar:/tmp/dusk-mixincheck/cp/13-guava.jar:/tmp/dusk-mixincheck/cp/14-gson.jar:/tmp/dusk-mixincheck/cp/15-log4j-api.jar:/tmp/dusk-mixincheck/cp/16-log4j-core.jar:/tmp/dusk-mixincheck/cp/17-jopt.jar:/tmp/dusk-mixincheck/cp/18-commons-io.jar:/tmp/dusk-mixincheck/cp/19-lwjgl.jar" \
      Harness /tmp/dusk-mixincheck/cp

Green is: `ALL 25 MIXINS APPLIED, ALL HOOKS WIRED`.

Why it's built the way it is (each line earned a crash or an hour):
- Tests the dev classes (`build/classes/java/main`, MCP names) against the
  MCP-named jars with the refmap disabled — one consistent name space. The
  shipped jar is the same sources after loom's searge reobf; check the refmap
  separately (`unzip -p build/libs/*.jar mixins.duskclient.refmap.json`).
- `MixinPlatformAgentFML` is deleted from the runtime jar copy: without a
  booted FML its remapper hookup NPEs. The Default agent handles the config.
- Config is registered into the PREINIT environment: this harness never
  advances phases the way the launch lifecycle does.
- The FMLTweaker stub is name-only — mixin's side detection reads the
  `Tweaks` blackboard looking for that class name.
- `-proc:none`: the jar's Mixin annotation processor otherwise runs on these
  sources and drags in gson.
- Recorder writes transformed bytes to disk because it loads in a different
  classloader than Harness.
