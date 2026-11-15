//! Forge for Minecraft 1.8.9, the version Hypixel is played on.
//!
//! Nothing here runs an installer: Forge's universal jar and its libraries
//! are plain downloads, pinned below, and it patches the game as it starts.
//! Macs also swap LWJGL for Legacy Fabric's 2.9.4 build, which holds native
//! Apple silicon libraries (vanilla's are Intel-only).

use crate::meta::{self, JavaVersion, Library, LibraryArtifact, LibraryDownloads, VersionJson};
use crate::{Error, Result};

pub const GAME_VERSION: &str = "1.8.9";
const FORGE_BUILD: &str = "1.8.9-11.15.1.2318-1.8.9";
const FORGE_JAR_SHA1: &str = "beda619c465af293e63952dd573c137c17c0a4cd";
const FORGE_JAR_SIZE: u64 = 3805686;

/// What Forge asks of the JVM: the jar is not Mojang-signed once modded, and
/// OptiFine's patches differ from the ones Forge expects.
pub const JVM_ARGS: &[&str] = &["-Dfml.ignoreInvalidMinecraftCertificates=true", "-Dfml.ignorePatchDiscrepancies=true"];

const GAME_ARGS: &str = "--username ${auth_player_name} --version ${version_name} --gameDir ${game_directory} \
--assetsDir ${assets_root} --assetIndex ${assets_index_name} --uuid ${auth_uuid} --accessToken ${auth_access_token} \
--userProperties ${user_properties} --userType ${user_type} --tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker";

const MOJANG: &str = "https://libraries.minecraft.net/";
const FORGE_MAVEN: &str = "https://maven.minecraftforge.net/";

/// (coordinates, repository, sha1, size)
const LIBRARIES: &[(&str, &str, &str, u64)] = &[
    ("net.minecraft:launchwrapper:1.12", MOJANG, "111e7bea9c968cdb3d06ef4632bf7ff0824d0f36", 32999),
    ("org.ow2.asm:asm-all:5.0.3", MOJANG, "4333508b8dd8ee72aa4e39afa713b3a74579b773", 241639),
    ("lzma:lzma:0.0.1", MOJANG, "521616dc7487b42bef0e803bd2fa3faf668101d7", 5762),
    ("net.sf.jopt-simple:jopt-simple:4.6", MOJANG, "306816fb57cf94f108a43c95731b08934dcae15c", 62477),
    ("java3d:vecmath:1.5.2", MOJANG, "79846ba34cbd89e2422d74d53752f993dcc2ccaf", 318956),
    ("net.sf.trove4j:trove4j:3.0.3", MOJANG, "42ccaf4761f0dfdfa805c9e340d99a755907e2dd", 2523218),
    ("jline:jline:2.13", FORGE_MAVEN, "2d9530d0a25daffaffda7c35037b046b627bb171", 248566),
    ("com.typesafe.akka:akka-actor_2.11:2.3.3", FORGE_MAVEN, "ed62e9fc709ca0f2ff1a3220daa8b70a2870078e", 2514991),
    ("com.typesafe:config:1.2.1", FORGE_MAVEN, "f771f71fdae3df231bcd54d5ca2d57f0bf93f467", 219554),
    ("org.scala-lang:scala-actors-migration_2.11:1.1.0", FORGE_MAVEN, "dfa8bc42b181d5b9f1a5dd147f8ae308b893eb6f", 58018),
    ("org.scala-lang:scala-compiler:2.11.1", FORGE_MAVEN, "56ea2e6c025e0821f28d73ca271218b8dd04926a", 13449765),
    ("org.scala-lang.plugins:scala-continuations-library_2.11:1.0.2", FORGE_MAVEN, "0e517c53a7e9acd6b1668c5a35eccbaa3bab9aac", 25365),
    ("org.scala-lang.plugins:scala-continuations-plugin_2.11.1:1.0.2", FORGE_MAVEN, "f361a3283452c57fa30c1ee69448995de23c60f7", 206575),
    ("org.scala-lang:scala-library:2.11.1", FORGE_MAVEN, "0e11da23da3eabab9f4777b9220e60d44c1aab6a", 5538130),
    ("org.scala-lang:scala-parser-combinators_2.11:1.0.1", FORGE_MAVEN, "f05d7345bf5a58924f2837c6c1f4d73a938e1ff0", 419701),
    ("org.scala-lang:scala-reflect:2.11.1", FORGE_MAVEN, "6580347e61cc7f8e802941e7fde40fa83b8badeb", 4372892),
    ("org.scala-lang:scala-swing_2.11:1.0.1", FORGE_MAVEN, "b1cdd92bd47b1e1837139c1c53020e86bb9112ae", 726500),
    ("org.scala-lang:scala-xml_2.11:1.0.2", FORGE_MAVEN, "7a80ec00aec122fba7cd4e0d4cdd87ff7e4cb6d0", 648679),
];

const LWJGL_VERSION: &str = "2.9.4+legacyfabric.17";
const LEGACY_FABRIC: &str = "https://repo.legacyfabric.net/legacyfabric/";
/// (artifact, sha1, size) of Legacy Fabric's LWJGL. Its repository has no
/// `.sha1` files; these hashes come from the jars the instance was tested with.
const LWJGL_JARS: &[(&str, &str, u64)] = &[
    ("lwjgl", "b0d3b134274c82aa401c90b42f0572063b1bf735", 1081524),
    ("lwjgl_util", "60b2bc63267f55fda5295d470e61d9fc672fa14c", 180575),
];
const LWJGL_NATIVES_SHA1: &str = "720009ad4fb3ebc510d2452e97dec3b0c6b4182f";
const LWJGL_NATIVES_SIZE: u64 = 1599360;

fn library(name: String, url: &str, sha1: &str, size: u64) -> Library {
    Library {
        name,
        downloads: None,
        url: Some(url.to_string()),
        sha1: Some(sha1.to_string()),
        size: Some(size),
        rules: None,
    }
}

fn lwjgl_artifact(artifact: &str, suffix: &str, sha1: &str, size: u64) -> LibraryArtifact {
    let path = format!("org/lwjgl/lwjgl/{artifact}/{LWJGL_VERSION}/{artifact}-{LWJGL_VERSION}{suffix}.jar");
    LibraryArtifact { url: format!("{LEGACY_FABRIC}{path}"), path, sha1: sha1.to_string(), size }
}

/// The Mac's LWJGL: two jars for the classpath, and one holding the Intel and
/// Apple silicon libraries, with no classpath entry of its own.
fn mac_lwjgl() -> Vec<Library> {
    let jar = |artifact: &str, sha1: &str, size: u64| Library {
        name: format!("org.lwjgl:{artifact}:{LWJGL_VERSION}"),
        downloads: Some(LibraryDownloads {
            artifact: Some(lwjgl_artifact(artifact, "", sha1, size)),
            classifiers: Default::default(),
        }),
        url: None,
        sha1: None,
        size: None,
        rules: None,
    };
    let mut libs: Vec<Library> = LWJGL_JARS.iter().map(|(a, sha1, size)| jar(a, sha1, *size)).collect();
    libs.push(Library {
        name: format!("org.lwjgl:lwjgl-platform:{LWJGL_VERSION}"),
        downloads: Some(LibraryDownloads {
            artifact: None,
            classifiers: meta::natives_classifier()
                .map(|c| {
                    let jar = lwjgl_artifact("lwjgl-platform", "-natives-osx", LWJGL_NATIVES_SHA1, LWJGL_NATIVES_SIZE);
                    (c.to_string(), jar)
                })
                .into_iter()
                .collect(),
        }),
        url: None,
        sha1: None,
        size: None,
        rules: None,
    });
    libs
}

/// Vanilla's Mac libraries that LWJGL 2.9.4 replaces or that have no Apple
/// silicon build. The game runs without Twitch streaming and controllers.
fn replaced_on_mac(library: &Library) -> bool {
    ["org.lwjgl", "tv.twitch", "net.java.jinput:jinput-platform"].iter().any(|p| library.name.starts_with(p))
}

fn merged(vanilla: &VersionJson, mac: bool, native_java: bool) -> Result<VersionJson> {
    if vanilla.id != GAME_VERSION {
        return Err(Error::Other(format!("Dusk runs Forge on {GAME_VERSION} only, not {}", vanilla.id)));
    }
    let mut version = vanilla.clone();
    version.id = format!("{GAME_VERSION}-forge{FORGE_BUILD}");
    version.main_class = "net.minecraft.launchwrapper.Launch".into();
    version.minecraft_arguments = Some(GAME_ARGS.into());
    version.arguments = None;

    let forge = format!("net.minecraftforge:forge:{FORGE_BUILD}:universal");
    let mut libraries = vec![library(forge, FORGE_MAVEN, FORGE_JAR_SHA1, FORGE_JAR_SIZE)];
    libraries.extend(LIBRARIES.iter().map(|(name, url, sha1, size)| library(name.to_string(), url, sha1, *size)));
    let pinned: std::collections::HashSet<String> = libraries.iter().map(|l| l.name.clone()).collect();
    libraries.extend(
        vanilla.libraries.iter().filter(|l| !pinned.contains(&l.name) && !(mac && replaced_on_mac(l))).cloned(),
    );
    if mac {
        libraries.extend(mac_lwjgl());
    }
    version.libraries = libraries;

    if native_java {
        version.java_version = Some(JavaVersion { component: crate::java::ZULU_8.into(), major_version: 8 });
    }
    Ok(version)
}

/// The version that starts Forge, built from vanilla 1.8.9's.
pub fn forge_version(vanilla: &VersionJson) -> Result<VersionJson> {
    merged(vanilla, cfg!(target_os = "macos"), cfg!(all(target_os = "macos", target_arch = "aarch64")))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn vanilla() -> VersionJson {
        serde_json::from_value(serde_json::json!({
            "id": "1.8.9",
            "type": "release",
            "mainClass": "net.minecraft.client.main.Main",
            "minecraftArguments": "--username ${auth_player_name}",
            "assets": "1.8",
            "downloads": {},
            "libraries": [
                {"name": "net.sf.jopt-simple:jopt-simple:4.6"},
                {"name": "com.paulscode:librarylwjglopenal:20100824"},
                {"name": "net.java.jinput:jinput:2.0.5"},
                {"name": "net.java.jinput:jinput-platform:2.0.5"},
                {"name": "org.lwjgl.lwjgl:lwjgl:2.9.2-nightly-20140822"},
                {"name": "org.lwjgl.lwjgl:lwjgl-platform:2.9.2-nightly-20140822"},
                {"name": "tv.twitch:twitch:6.5"}
            ]
        }))
        .unwrap()
    }

    #[test]
    fn only_1_8_9_gets_forge() {
        let mut other = vanilla();
        other.id = "1.21.11".into();
        assert!(forge_version(&other).is_err());
    }

    #[test]
    fn forge_starts_first_and_the_game_args_tweak_it() {
        let v = merged(&vanilla(), false, false).unwrap();
        assert_eq!(v.id, "1.8.9-forge1.8.9-11.15.1.2318-1.8.9");
        assert_eq!(v.main_class, "net.minecraft.launchwrapper.Launch");
        assert!(v.arguments.is_none());
        assert!(v.minecraft_arguments.unwrap().ends_with("--tweakClass net.minecraftforge.fml.common.launcher.FMLTweaker"));

        let forge = v.libraries[0].resolve_artifact().unwrap();
        assert_eq!(
            forge.url,
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.8.9-11.15.1.2318-1.8.9/forge-1.8.9-11.15.1.2318-1.8.9-universal.jar"
        );
        assert_eq!(forge.sha1.as_deref(), Some(FORGE_JAR_SHA1));
        let jopt = v.libraries.iter().filter(|l| l.name.starts_with("net.sf.jopt-simple")).count();
        assert_eq!(jopt, 1);
    }

    #[test]
    fn a_mac_swaps_lwjgl_and_keeps_the_rest() {
        let v = merged(&vanilla(), true, false).unwrap();
        let names: Vec<&str> = v.libraries.iter().map(|l| l.name.as_str()).collect();
        assert!(!names.iter().any(|n| n.contains("2.9.2") || n.starts_with("tv.twitch") || n.contains("jinput-platform")));
        assert!(names.contains(&"net.java.jinput:jinput:2.0.5"));
        assert!(names.contains(&"com.paulscode:librarylwjglopenal:20100824"));

        let jars: Vec<_> = v.libraries.iter().filter_map(|l| l.resolve_artifact()).collect();
        assert_eq!(
            jars[jars.len() - 2].url,
            "https://repo.legacyfabric.net/legacyfabric/org/lwjgl/lwjgl/lwjgl/2.9.4+legacyfabric.17/lwjgl-2.9.4+legacyfabric.17.jar"
        );
        assert!(jars.last().unwrap().url.contains("/lwjgl_util-"));
        // the natives jar is not a classpath entry
        assert!(v.libraries.last().unwrap().resolve_artifact().is_none());
        if let Some(classifier) = meta::natives_classifier() {
            let natives = &v.libraries.last().unwrap().downloads.as_ref().unwrap().classifiers[classifier];
            assert_eq!(
                natives.url,
                "https://repo.legacyfabric.net/legacyfabric/org/lwjgl/lwjgl/lwjgl-platform/2.9.4+legacyfabric.17/lwjgl-platform-2.9.4+legacyfabric.17-natives-osx.jar"
            );
        }
    }

    #[test]
    fn other_systems_keep_vanilla_lwjgl_and_mojang_java() {
        let v = merged(&vanilla(), false, false).unwrap();
        assert!(v.libraries.iter().any(|l| l.name.starts_with("org.lwjgl.lwjgl:lwjgl:2.9.2")));
        assert_eq!(v.effective_java().component, "jre-legacy");
    }

    #[test]
    fn apple_silicon_gets_the_native_java_8() {
        let v = merged(&vanilla(), true, true).unwrap();
        assert_eq!(v.effective_java().component, crate::java::ZULU_8);
        assert_eq!(v.effective_java().major_version, 8);
    }
}
