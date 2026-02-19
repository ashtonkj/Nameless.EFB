// terrain-preprocessor/src/main.rs
// Converts Copernicus DEM GLO-30 HGT files to the EFB internal
// 512×512 float16 tile format used by TerrainTileCache on Android.

use anyhow::{Context, Result};
use clap::Parser;
use half::f16;
use std::path::{Path, PathBuf};

// ---------------------------------------------------------------------------
// CLI args
// ---------------------------------------------------------------------------

#[derive(Parser)]
#[command(name = "terrain-preprocessor", about = "Convert HGT tiles to EFB float16 format")]
struct Args {
    /// Input directory containing Copernicus .hgt files (or a single .hgt file)
    #[arg(short, long)]
    input: PathBuf,

    /// Output directory for .f16 tile files
    #[arg(short, long)]
    output: PathBuf,
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/// HGT files from Copernicus GLO-30 are 3601×3601 signed-16-bit samples.
const HGT_DIM: usize = 3601;
/// Output tile resolution.
const TILE_DIM: usize = 512;
/// Void/ocean samples in HGT files.
const HGT_VOID: i16 = -32768;

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

fn main() -> Result<()> {
    let args = Args::parse();

    std::fs::create_dir_all(&args.output)
        .context("Failed to create output directory")?;

    if args.input.is_file() {
        process_hgt(&args.input, &args.output)?;
    } else {
        for entry in std::fs::read_dir(&args.input)? {
            let path = entry?.path();
            if path.extension().and_then(|e| e.to_str()).map(|e| e.to_uppercase()) == Some("HGT".to_string()) {
                eprintln!("Processing {}...", path.display());
                if let Err(e) = process_hgt(&path, &args.output) {
                    eprintln!("  Warning: {e}");
                }
            }
        }
    }

    Ok(())
}

fn process_hgt(hgt_path: &Path, output_dir: &Path) -> Result<()> {
    let hgt = read_hgt(hgt_path)?;
    let tile = downsample_to_512(&hgt);
    let bytes = encode_f16_tile(&tile);
    let out_name = tile_name_from_path(hgt_path);
    let out_path = output_dir.join(out_name);
    std::fs::write(&out_path, &bytes)
        .with_context(|| format!("Cannot write {}", out_path.display()))?;
    Ok(())
}

// ---------------------------------------------------------------------------
// HGT reader
// ---------------------------------------------------------------------------

/// Reads a 3601×3601 HGT file (big-endian i16) into a flat Vec<i16>.
/// Row 0 is the northernmost row; col 0 is the westernmost column.
pub fn read_hgt(path: &Path) -> Result<Vec<i16>> {
    let bytes = std::fs::read(path)
        .with_context(|| format!("Cannot read {}", path.display()))?;

    let expected = HGT_DIM * HGT_DIM * 2;
    anyhow::ensure!(
        bytes.len() >= expected,
        "HGT file too small: {} bytes (expected {})",
        bytes.len(), expected
    );

    let samples: Vec<i16> = bytes[..expected]
        .chunks_exact(2)
        .map(|b| i16::from_be_bytes([b[0], b[1]]))
        .collect();

    Ok(samples)
}

// ---------------------------------------------------------------------------
// Downsampling
// ---------------------------------------------------------------------------

/// Downsamples a 3601×3601 HGT grid to 512×512 using a 7×7 averaging window.
/// Void samples (HGT_VOID) are excluded from the average.
/// Preserves peak elevations — important for TAWS accuracy.
pub fn downsample_to_512(hgt: &[i16]) -> Vec<f32> {
    let window = HGT_DIM / TILE_DIM; // ≈ 7
    let half_w = window / 2;
    let mut out = Vec::with_capacity(TILE_DIM * TILE_DIM);

    for row in 0..TILE_DIM {
        for col in 0..TILE_DIM {
            // Map output pixel to input centre pixel
            let in_row = (row * HGT_DIM / TILE_DIM).min(HGT_DIM - 1);
            let in_col = (col * HGT_DIM / TILE_DIM).min(HGT_DIM - 1);

            let r_start = in_row.saturating_sub(half_w);
            let r_end   = (in_row + half_w + 1).min(HGT_DIM);
            let c_start = in_col.saturating_sub(half_w);
            let c_end   = (in_col + half_w + 1).min(HGT_DIM);

            let mut sum   = 0.0f64;
            let mut count = 0u32;
            for r in r_start..r_end {
                for c in c_start..c_end {
                    let v = hgt[r * HGT_DIM + c];
                    if v != HGT_VOID {
                        sum   += v as f64;
                        count += 1;
                    }
                }
            }

            out.push(if count > 0 { (sum / count as f64) as f32 } else { 0.0 });
        }
    }

    out
}

// ---------------------------------------------------------------------------
// Float16 encoding
// ---------------------------------------------------------------------------

/// Encodes 512×512 f32 elevations as big-endian float16 bytes.
/// Output: 512 × 512 × 2 = 524,288 bytes.
pub fn encode_f16_tile(tile: &[f32]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(tile.len() * 2);
    for &v in tile {
        let h = f16::from_f32(v);
        bytes.extend_from_slice(&h.to_be_bytes());
    }
    bytes
}

// ---------------------------------------------------------------------------
// Tile naming
// ---------------------------------------------------------------------------

/// Derives output filename from the HGT path.
/// e.g. "N26E028.hgt" → "N26_E028.f16" (matches TerrainTileCache convention)
pub fn tile_name_from_path(path: &Path) -> String {
    let stem = path.file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("UNKNOWN");
    // Standard SRTM naming: S34E018 or N26E028 (7 chars)
    // Split at position 3 to insert '_': "S34" + "_" + "E018" → "S34_E018"
    let name = if stem.len() >= 7 {
        format!("{}_{}.f16", &stem[..3], &stem[3..])
    } else {
        format!("{stem}.f16")
    };
    name
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds a synthetic 3601×3601 HGT grid with a constant elevation value.
    fn synthetic_hgt(elevation_m: i16) -> Vec<i16> {
        vec![elevation_m; HGT_DIM * HGT_DIM]
    }

    #[test]
    fn terrain_tile_roundtrip() {
        // FAOR elevation: 1753 m
        let hgt = synthetic_hgt(1753);
        let tile = downsample_to_512(&hgt);
        let bytes = encode_f16_tile(&tile);

        // Decode back
        let decoded: Vec<f32> = bytes
            .chunks_exact(2)
            .map(|b| f16::from_be_bytes([b[0], b[1]]).to_f32())
            .collect();

        // All pixels should decode close to 1753.0 (float16 has ~3 decimal digit precision)
        let centre = decoded[TILE_DIM / 2 * TILE_DIM + TILE_DIM / 2];
        assert!(
            (centre - 1753.0).abs() < 5.0,
            "Decoded elevation {centre} too far from 1753.0"
        );
        assert_eq!(decoded.len(), TILE_DIM * TILE_DIM);
    }

    #[test]
    fn void_samples_become_zero() {
        let hgt = synthetic_hgt(HGT_VOID);
        let tile = downsample_to_512(&hgt);
        // All void → all zero
        assert!(tile.iter().all(|&v| v == 0.0));
    }

    #[test]
    fn output_is_correct_byte_length() {
        let hgt = synthetic_hgt(100);
        let tile = downsample_to_512(&hgt);
        let bytes = encode_f16_tile(&tile);
        assert_eq!(bytes.len(), TILE_DIM * TILE_DIM * 2);
    }

    #[test]
    fn downsample_output_size_is_512x512() {
        let hgt = synthetic_hgt(0);
        let tile = downsample_to_512(&hgt);
        assert_eq!(tile.len(), TILE_DIM * TILE_DIM);
    }

    #[test]
    fn tile_name_derives_correctly() {
        let path = Path::new("/data/tiles/N26E028.hgt");
        assert_eq!(tile_name_from_path(path), "N26_E028.f16");
        let path2 = Path::new("/data/S34E018.HGT");
        assert_eq!(tile_name_from_path(path2), "S34_E018.f16");
    }
}
