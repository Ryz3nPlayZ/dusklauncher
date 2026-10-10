//! Packs zipped one folder too deep. Compressing a pack's folder (Finder's
//! Compress, Explorer's Send to → Compressed folder) gives `Pack/pack.mcmeta`
//! rather than `pack.mcmeta`, often with macOS's `__MACOSX/` and `.DS_Store`
//! alongside, and the game won't list such a pack. These are rewritten with
//! the folder's contents at the root; the entries are copied as they are,
//! not recompressed.

use std::io::{Read, Seek};
use std::path::Path;

/// Files a zip picks up from the OS that no pack needs.
fn junk(name: &str) -> bool {
    name.starts_with("__MACOSX/") || name == ".DS_Store" || name.ends_with("/.DS_Store")
}

/// What marks the root of a resource pack (pack.mcmeta) or a shader pack (shaders/).
fn pack_root(rest: &str) -> bool {
    rest == "pack.mcmeta" || rest.starts_with("shaders/")
}

/// The one folder (with its trailing slash) a pack zip's contents sit in,
/// when the root isn't a pack and that folder is.
pub fn nested_root<R: Read + Seek>(zip: &zip::ZipArchive<R>) -> Option<String> {
    let names: Vec<&str> = zip.file_names().filter(|n| !junk(n)).collect();
    if names.iter().any(|n| pack_root(n)) {
        return None;
    }
    let (top, _) = names.first()?.split_once('/')?;
    let prefix = format!("{top}/");
    let inside = |n: &&str| n.starts_with(prefix.as_str());
    (names.iter().all(inside) && names.iter().any(|n| pack_root(&n[prefix.len()..]))).then_some(prefix)
}

fn open(path: &Path) -> Option<zip::ZipArchive<std::fs::File>> {
    zip::ZipArchive::new(std::fs::File::open(path).ok()?).ok()
}

/// Writes `src` to `dst` with `prefix` taken off every entry and the junk left out.
fn write_flattened(src: &Path, dst: &Path, prefix: &str) -> Result<(), String> {
    let part = dst.with_extension("zip.part");
    {
        let mut zip = open(src).ok_or("Couldn't read the pack")?;
        let mut out = zip::ZipWriter::new(std::fs::File::create(&part).map_err(|e| e.to_string())?);
        for i in 0..zip.len() {
            let entry = zip.by_index_raw(i).map_err(|e| e.to_string())?;
            let name = entry.name().to_string();
            match name.strip_prefix(prefix) {
                Some(rest) if !rest.is_empty() && !junk(&name) => {
                    out.raw_copy_file_rename(entry, rest).map_err(|e| e.to_string())?
                }
                _ => {}
            }
        }
        out.finish().map_err(|e| e.to_string())?;
    }
    // the reader is closed by now, so this also works over `src` itself on Windows
    std::fs::rename(&part, dst).map_err(|e| {
        let _ = std::fs::remove_file(&part);
        e.to_string()
    })
}

/// Copies a pack zip to `dst`, taking it out of its extra folder on the way.
pub fn copy_pack(src: &Path, dst: &Path) -> Result<(), String> {
    match open(src).as_ref().and_then(nested_root) {
        Some(prefix) => write_flattened(src, dst, &prefix),
        None => std::fs::copy(src, dst).map(|_| ()).map_err(|e| e.to_string()),
    }
}

/// Fixes the nested pack zips already in `dir` (packs put there by hand), in place.
pub fn repair_dir(dir: &Path) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        if !entry.file_name().to_string_lossy().to_ascii_lowercase().ends_with(".zip") {
            continue;
        }
        if let Some(prefix) = open(&path).as_ref().and_then(nested_root) {
            if let Err(e) = write_flattened(&path, &path, &prefix) {
                eprintln!("[packfix] couldn't unnest {}: {e}", path.display());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn zip_with(path: &Path, entries: &[&str]) {
        let mut z = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
        for e in entries {
            if e.ends_with('/') {
                z.add_directory(*e, zip::write::SimpleFileOptions::default()).unwrap();
            } else {
                z.start_file(*e, zip::write::SimpleFileOptions::default()).unwrap();
                z.write_all(e.as_bytes()).unwrap();
            }
        }
        z.finish().unwrap();
    }

    fn names(path: &Path) -> Vec<String> {
        let mut n: Vec<String> = open(path).unwrap().file_names().map(str::to_string).collect();
        n.sort();
        n
    }

    #[test]
    fn a_pack_compressed_from_its_folder_is_moved_to_the_root() {
        let tmp = std::env::temp_dir().join(format!("dusk-packfix-{}", std::process::id()));
        std::fs::create_dir_all(&tmp).unwrap();
        let pack = tmp.join("Sky v2.zip");
        zip_with(
            &pack,
            &[
                "Sky v2/",
                "Sky v2/pack.mcmeta",
                "Sky v2/.DS_Store",
                "Sky v2/assets/minecraft/optifine/sky/world0/sky1.properties",
                "__MACOSX/Sky v2/._pack.mcmeta",
            ],
        );
        repair_dir(&tmp);
        assert_eq!(names(&pack), ["assets/minecraft/optifine/sky/world0/sky1.properties", "pack.mcmeta"]);
        let mut body = String::new();
        open(&pack).unwrap().by_name("pack.mcmeta").unwrap().read_to_string(&mut body).unwrap();
        assert_eq!(body, "Sky v2/pack.mcmeta");

        // a pack that's fine, a shader pack in a folder, and a zip that isn't a pack
        let fine = tmp.join("fine.zip");
        zip_with(&fine, &["pack.mcmeta", "assets/x.png"]);
        let shader = tmp.join("shader.zip");
        zip_with(&shader, &["BSL/shaders/final.fsh"]);
        let other = tmp.join("other.zip");
        zip_with(&other, &["stuff/readme.txt"]);
        repair_dir(&tmp);
        assert_eq!(names(&fine), ["assets/x.png", "pack.mcmeta"]);
        assert_eq!(names(&shader), ["shaders/final.fsh"]);
        assert_eq!(names(&other), ["stuff/readme.txt"]);
        let _ = std::fs::remove_dir_all(&tmp);
    }
}
