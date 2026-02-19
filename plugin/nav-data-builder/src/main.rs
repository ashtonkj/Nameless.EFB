// nav-data-builder/src/main.rs
// Parses OurAirports CSV + X-Plane nav data files + OpenAIP XML
// into a SQLite database matching the Room entity schema (camelCase columns).

use anyhow::{Context, Result};
use clap::Parser;
use rusqlite::{params, Connection};
use std::path::{Path, PathBuf};

// ---------------------------------------------------------------------------
// CLI args
// ---------------------------------------------------------------------------

#[derive(Parser)]
#[command(name = "nav-data-builder", about = "Build EFB navigation database")]
struct Args {
    #[arg(long)] airports_csv:    PathBuf,
    #[arg(long)] runways_csv:     PathBuf,
    #[arg(long)] navaids_csv:     PathBuf,
    #[arg(long)] frequencies_csv: PathBuf,
    #[arg(long)] earth_nav_dat:   PathBuf,
    #[arg(long)] earth_fix_dat:   PathBuf,
    #[arg(long)] earth_awy_dat:   PathBuf,
    #[arg(long)] openaip_dir:     PathBuf,
    #[arg(short, long)] output:   PathBuf,
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

fn main() -> Result<()> {
    let args = Args::parse();

    if args.output.exists() {
        std::fs::remove_file(&args.output)?;
    }

    let conn = Connection::open(&args.output)
        .context("Failed to open output database")?;
    conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;")?;

    run_migrations(&conn)?;

    eprintln!("Parsing OurAirports airports...");
    parse_ourairports_airports(&conn, &args.airports_csv)?;
    eprintln!("Parsing OurAirports runways...");
    parse_ourairports_runways(&conn, &args.runways_csv)?;
    eprintln!("Parsing OurAirports navaids...");
    parse_ourairports_navaids(&conn, &args.navaids_csv)?;
    eprintln!("Parsing OurAirports frequencies...");
    parse_ourairports_frequencies(&conn, &args.frequencies_csv)?;
    eprintln!("Parsing X-Plane earth_nav.dat...");
    parse_xplane_nav_dat(&conn, &args.earth_nav_dat)?;
    eprintln!("Parsing X-Plane earth_fix.dat...");
    parse_xplane_fix_dat(&conn, &args.earth_fix_dat)?;
    eprintln!("Parsing X-Plane earth_awy.dat...");
    parse_xplane_awy_dat(&conn, &args.earth_awy_dat)?;
    eprintln!("Parsing OpenAIP airspaces...");
    parse_openaip_airspaces(&conn, &args.openaip_dir)?;
    eprintln!("Parsing OpenAIP obstacles...");
    parse_openaip_obstacles(&conn, &args.openaip_dir)?;
    eprintln!("Building R-tree indexes...");
    populate_rtree_indexes(&conn)?;

    eprintln!("Done. Row counts:");
    for table in ["airports", "navaids", "fixes", "airways", "airspaces", "obstacles"] {
        let count: i64 = conn.query_row(
            &format!("SELECT COUNT(*) FROM {table}"), [], |r| r.get(0),
        )?;
        eprintln!("  {table}: {count}");
    }

    Ok(())
}

// ---------------------------------------------------------------------------
// Schema — column names match Room entity field names exactly (camelCase)
// ---------------------------------------------------------------------------

pub fn run_migrations(conn: &Connection) -> Result<()> {
    conn.execute_batch(MIGRATIONS).context("Migration failed")?;
    Ok(())
}

const MIGRATIONS: &str = r#"
CREATE TABLE IF NOT EXISTS airports (
    icao         TEXT PRIMARY KEY NOT NULL,
    name         TEXT NOT NULL DEFAULT '',
    latitude     REAL NOT NULL DEFAULT 0.0,
    longitude    REAL NOT NULL DEFAULT 0.0,
    elevationFt  INTEGER NOT NULL DEFAULT 0,
    airportType  TEXT NOT NULL DEFAULT '',
    isTowered    INTEGER NOT NULL DEFAULT 0,
    isMilitary   INTEGER NOT NULL DEFAULT 0,
    countryCode  TEXT NOT NULL DEFAULT '',
    municipality TEXT NOT NULL DEFAULT '',
    source       TEXT NOT NULL DEFAULT 'ourairports'
);
CREATE VIRTUAL TABLE IF NOT EXISTS airports_rtree USING rtree(
    id, lat_min, lat_max, lon_min, lon_max
);
CREATE TABLE IF NOT EXISTS runways (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    airportIcao TEXT NOT NULL DEFAULT '',
    ident       TEXT NOT NULL DEFAULT '',
    lengthFt    INTEGER NOT NULL DEFAULT 0,
    widthFt     INTEGER NOT NULL DEFAULT 0,
    surface     TEXT NOT NULL DEFAULT '',
    latHe       REAL NOT NULL DEFAULT 0.0,
    lonHe       REAL NOT NULL DEFAULT 0.0,
    latLe       REAL NOT NULL DEFAULT 0.0,
    lonLe       REAL NOT NULL DEFAULT 0.0,
    headingDeg  REAL NOT NULL DEFAULT 0.0,
    ilsFreqHz   INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (airportIcao) REFERENCES airports(icao) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_rwy_airport ON runways(airportIcao);
CREATE TABLE IF NOT EXISTS navaids (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    identifier        TEXT NOT NULL DEFAULT '',
    name              TEXT NOT NULL DEFAULT '',
    type              TEXT NOT NULL DEFAULT '',
    latitude          REAL NOT NULL DEFAULT 0.0,
    longitude         REAL NOT NULL DEFAULT 0.0,
    elevationFt       INTEGER NOT NULL DEFAULT 0,
    frequencyHz       INTEGER NOT NULL DEFAULT 0,
    magneticVariation REAL NOT NULL DEFAULT 0.0,
    rangeNm           INTEGER NOT NULL DEFAULT 0,
    icaoRegion        TEXT NOT NULL DEFAULT '',
    airportIcao       TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_nav_ident  ON navaids(identifier);
CREATE INDEX IF NOT EXISTS idx_nav_region ON navaids(icaoRegion);
CREATE VIRTUAL TABLE IF NOT EXISTS navaids_rtree USING rtree(
    id, lat_min, lat_max, lon_min, lon_max
);
CREATE TABLE IF NOT EXISTS fixes (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    identifier TEXT NOT NULL DEFAULT '',
    latitude   REAL NOT NULL DEFAULT 0.0,
    longitude  REAL NOT NULL DEFAULT 0.0,
    icaoRegion TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_fix_ident  ON fixes(identifier);
CREATE INDEX IF NOT EXISTS idx_fix_region ON fixes(icaoRegion);
CREATE TABLE IF NOT EXISTS airways (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    identifier TEXT NOT NULL DEFAULT '',
    type       TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_awy_ident ON airways(identifier);
CREATE TABLE IF NOT EXISTS airway_segments (
    id        INTEGER PRIMARY KEY AUTOINCREMENT,
    airwayId  INTEGER NOT NULL DEFAULT 0,
    fixFromId INTEGER NOT NULL DEFAULT 0,
    fixToId   INTEGER NOT NULL DEFAULT 0,
    level     TEXT NOT NULL DEFAULT 'B',
    direction TEXT NOT NULL DEFAULT 'N',
    minAltFt  INTEGER NOT NULL DEFAULT 0,
    maxAltFt  INTEGER NOT NULL DEFAULT 99000
);
CREATE INDEX IF NOT EXISTS idx_awyseg_airway ON airway_segments(airwayId);
CREATE INDEX IF NOT EXISTS idx_awyseg_from   ON airway_segments(fixFromId);
CREATE INDEX IF NOT EXISTS idx_awyseg_to     ON airway_segments(fixToId);
CREATE TABLE IF NOT EXISTS airspaces (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL DEFAULT '',
    airspaceClass TEXT NOT NULL DEFAULT '',
    floorFt       INTEGER NOT NULL DEFAULT 0,
    ceilingFt     INTEGER NOT NULL DEFAULT 99000,
    floorRef      TEXT NOT NULL DEFAULT 'MSL',
    ceilingRef    TEXT NOT NULL DEFAULT 'MSL',
    countryCode   TEXT NOT NULL DEFAULT '',
    geometryJson  TEXT NOT NULL DEFAULT '{}',
    bboxLatMin    REAL NOT NULL DEFAULT 0.0,
    bboxLatMax    REAL NOT NULL DEFAULT 0.0,
    bboxLonMin    REAL NOT NULL DEFAULT 0.0,
    bboxLonMax    REAL NOT NULL DEFAULT 0.0
);
CREATE TABLE IF NOT EXISTS obstacles (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    type           TEXT NOT NULL DEFAULT '',
    latitude       REAL NOT NULL DEFAULT 0.0,
    longitude      REAL NOT NULL DEFAULT 0.0,
    heightAglFt    INTEGER NOT NULL DEFAULT 0,
    elevationMslFt INTEGER NOT NULL DEFAULT 0,
    source         TEXT NOT NULL DEFAULT 'openaip',
    countryCode    TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS airport_frequencies (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    airportIcao TEXT NOT NULL DEFAULT '',
    type        TEXT NOT NULL DEFAULT '',
    description TEXT NOT NULL DEFAULT '',
    frequencyHz INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_freq_airport ON airport_frequencies(airportIcao);
CREATE TABLE IF NOT EXISTS procedures (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    airportIcao   TEXT NOT NULL DEFAULT '',
    type          TEXT NOT NULL DEFAULT '',
    identifier    TEXT NOT NULL DEFAULT '',
    runway        TEXT NOT NULL DEFAULT '',
    transition    TEXT NOT NULL DEFAULT '',
    waypointsJson TEXT NOT NULL DEFAULT '[]',
    airacCycle    TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_proc_airport ON procedures(airportIcao);
"#;

// ---------------------------------------------------------------------------
// OurAirports CSV parsers
// ---------------------------------------------------------------------------

// airports.csv: id,ident,type,name,latitude_deg,longitude_deg,elevation_ft,
//   continent,iso_country,iso_region,municipality,...
pub fn parse_ourairports_airports(conn: &Connection, path: &Path) -> Result<()> {
    let mut rdr = csv::Reader::from_path(path)
        .with_context(|| format!("Cannot open {}", path.display()))?;

    conn.execute_batch("BEGIN")?;
    let mut stmt = conn.prepare(
        "INSERT OR IGNORE INTO airports
         (icao,name,latitude,longitude,elevationFt,airportType,
          isTowered,isMilitary,countryCode,municipality,source)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,'ourairports')",
    )?;

    for rec in rdr.records() {
        let r = rec?;
        let ident = r.get(1).unwrap_or("").trim().to_string();
        if ident.len() < 2 { continue; }

        let atype   = r.get(2).unwrap_or("").trim().to_string();
        let name    = r.get(3).unwrap_or("").trim().to_string();
        let lat: f64  = r.get(4).unwrap_or("0").trim().parse().unwrap_or(0.0);
        let lon: f64  = r.get(5).unwrap_or("0").trim().parse().unwrap_or(0.0);
        let elev: i64 = r.get(6).unwrap_or("0").trim().parse().unwrap_or(0);
        let country = r.get(8).unwrap_or("").trim().to_string();
        let muni    = r.get(10).unwrap_or("").trim().to_string();

        let is_towered: i32 =
            if matches!(atype.as_str(), "large_airport" | "medium_airport") { 1 } else { 0 };
        let is_military: i32 = {
            let n = name.to_lowercase();
            if n.contains("military") || n.contains("air base") || n.contains("air force") { 1 } else { 0 }
        };

        stmt.execute(params![ident, name, lat, lon, elev, atype,
                              is_towered, is_military, country, muni])?;
    }
    drop(stmt);
    conn.execute_batch("COMMIT")?;
    Ok(())
}

// runways.csv: id,airport_ref,airport_ident,length_ft,width_ft,surface,...
//   le_ident(8),le_lat(9),le_lon(10),...,he_ident(14),he_lat(15),he_lon(16),...,he_heading(18)
pub fn parse_ourairports_runways(conn: &Connection, path: &Path) -> Result<()> {
    let mut rdr = csv::Reader::from_path(path)
        .with_context(|| format!("Cannot open {}", path.display()))?;

    conn.execute_batch("BEGIN")?;
    let mut stmt = conn.prepare(
        "INSERT INTO runways
         (airportIcao,ident,lengthFt,widthFt,surface,
          latHe,lonHe,latLe,lonLe,headingDeg,ilsFreqHz)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,0)",
    )?;

    for rec in rdr.records() {
        let r = rec?;
        let airport = r.get(2).unwrap_or("").trim().to_string();
        if airport.len() < 2 { continue; }

        let length: i64 = r.get(3).unwrap_or("0").trim().parse().unwrap_or(0);
        let width:  i64 = r.get(4).unwrap_or("0").trim().parse().unwrap_or(0);
        let surface = r.get(5).unwrap_or("").trim().to_string();
        let ident   = r.get(14).unwrap_or("").trim().to_string();
        let lat_he: f64 = r.get(15).unwrap_or("0").trim().parse().unwrap_or(0.0);
        let lon_he: f64 = r.get(16).unwrap_or("0").trim().parse().unwrap_or(0.0);
        let lat_le: f64 = r.get(9).unwrap_or("0").trim().parse().unwrap_or(0.0);
        let lon_le: f64 = r.get(10).unwrap_or("0").trim().parse().unwrap_or(0.0);
        let heading: f64 = r.get(18).unwrap_or("0").trim().parse().unwrap_or(0.0);

        stmt.execute(params![airport, ident, length, width, surface,
                              lat_he, lon_he, lat_le, lon_le, heading])?;
    }
    drop(stmt);
    conn.execute_batch("COMMIT")?;
    Ok(())
}

// navaids.csv: id,filename,ident,name,type,frequency_khz,lat,lon,elev,
//   iso_country,...,magnetic_variation_deg(16),...,associated_airport(19)
pub fn parse_ourairports_navaids(conn: &Connection, path: &Path) -> Result<()> {
    let mut rdr = csv::Reader::from_path(path)
        .with_context(|| format!("Cannot open {}", path.display()))?;

    conn.execute_batch("BEGIN")?;
    let mut stmt = conn.prepare(
        "INSERT INTO navaids
         (identifier,name,type,latitude,longitude,elevationFt,
          frequencyHz,magneticVariation,rangeNm,icaoRegion,airportIcao)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,130,?9,?10)",
    )?;

    for rec in rdr.records() {
        let r = rec?;
        let ident   = r.get(2).unwrap_or("").trim().to_string();
        let name    = r.get(3).unwrap_or("").trim().to_string();
        let raw_type = r.get(4).unwrap_or("").trim().to_uppercase();

        let navtype = match raw_type.as_str() {
            "VOR-DME" | "VORDME" => "VOR-DME",
            "VOR"                => "VOR",
            "NDB"                => "NDB",
            "TACAN"              => "VOR-DME",
            "DME"                => "DME",
            _                    => continue,
        };

        if ident.is_empty() { continue; }

        let freq_khz: i64 = r.get(5).unwrap_or("0").trim().parse().unwrap_or(0);
        let lat: f64  = r.get(6).unwrap_or("0").trim().parse().unwrap_or(0.0);
        let lon: f64  = r.get(7).unwrap_or("0").trim().parse().unwrap_or(0.0);
        let elev: i64 = r.get(8).unwrap_or("0").trim().parse().unwrap_or(0);
        let country   = r.get(9).unwrap_or("").trim().to_string();
        let mag_var: f64 = r.get(16).unwrap_or("0").trim().parse().unwrap_or(0.0);
        let airport   = r.get(19).unwrap_or("").trim().to_string();

        stmt.execute(params![ident, name, navtype, lat, lon, elev,
                              freq_khz * 1000, mag_var, country, airport])?;
    }
    drop(stmt);
    conn.execute_batch("COMMIT")?;
    Ok(())
}

// frequencies.csv: id,airport_ref,airport_ident,type,description,frequency_mhz
pub fn parse_ourairports_frequencies(conn: &Connection, path: &Path) -> Result<()> {
    let mut rdr = csv::Reader::from_path(path)
        .with_context(|| format!("Cannot open {}", path.display()))?;

    conn.execute_batch("BEGIN")?;
    let mut stmt = conn.prepare(
        "INSERT INTO airport_frequencies (airportIcao,type,description,frequencyHz)
         VALUES (?1,?2,?3,?4)",
    )?;

    for rec in rdr.records() {
        let r = rec?;
        let airport = r.get(2).unwrap_or("").trim().to_string();
        if airport.len() < 2 { continue; }
        let raw_type  = r.get(3).unwrap_or("").trim().to_uppercase();
        let freq_type = normalise_freq_type(&raw_type);
        let desc      = r.get(4).unwrap_or("").trim().to_string();
        let freq_mhz: f64 = r.get(5).unwrap_or("0").trim().parse().unwrap_or(0.0);
        let freq_hz = (freq_mhz * 1_000_000.0).round() as i64;

        stmt.execute(params![airport, freq_type, desc, freq_hz])?;
    }
    drop(stmt);
    conn.execute_batch("COMMIT")?;
    Ok(())
}

fn normalise_freq_type(raw: &str) -> &'static str {
    match raw {
        "TWR" | "TOWER"                         => "TWR",
        "GND" | "GROUND" | "DEL"               => "GND",
        "ATIS"                                   => "ATIS",
        "APP" | "APPROACH"                      => "APP",
        "DEP" | "DEPARTURE"                     => "DEP",
        "CTR" | "CENTER" | "CENTRE" | "FSS"    => "CTR",
        "UNICOM" | "CTAF" | "RDO" | "RADIO"    => "UNICOM",
        "MULTICOM"                               => "MULTICOM",
        _                                        => "UNICOM",
    }
}

// ---------------------------------------------------------------------------
// X-Plane nav data parsers
// ---------------------------------------------------------------------------

// earth_nav.dat type codes:
//   2=NDB  3=VOR  4=ILS LOC  5=ILS GS  6-8=markers  9=DME  12=FMS  13=VOR-DME
// Layout: code lat lon elev freq_raw range_nm var ident region airport name...
pub fn parse_xplane_nav_dat(conn: &Connection, path: &Path) -> Result<()> {
    let content = std::fs::read_to_string(path)
        .with_context(|| format!("Cannot read {}", path.display()))?;

    conn.execute_batch("BEGIN")?;
    let mut stmt = conn.prepare(
        "INSERT INTO navaids
         (identifier,name,type,latitude,longitude,elevationFt,
          frequencyHz,magneticVariation,rangeNm,icaoRegion,airportIcao)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
    )?;

    for line in content.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('I') || line.starts_with('A') { continue; }
        if line == "99" { break; }

        let f: Vec<&str> = line.splitn(12, ' ').collect();
        if f.len() < 10 { continue; }

        let code: u8 = f[0].parse().unwrap_or(0);
        let (navtype, freq_scale): (&str, i64) = match code {
            2  => ("NDB",     1_000),      // kHz → Hz
            3  => ("VOR",    10_000),      // 100kHz → Hz
            4  => ("ILS",    10_000),
            5 | 6 | 7 | 8 => continue,
            9  => ("DME",    10_000),
            12 => ("RNAV",       0),
            13 => ("VOR-DME", 10_000),
            _  => continue,
        };

        let lat: f64    = f[1].parse().unwrap_or(0.0);
        let lon: f64    = f[2].parse().unwrap_or(0.0);
        let elev: i64   = f[3].parse().unwrap_or(0);
        let raw_freq: i64 = f[4].parse().unwrap_or(0);
        let range_nm: i64 = f[5].parse().unwrap_or(0);
        let mag_var: f64  = f[6].parse().unwrap_or(0.0);
        let ident  = f[7];
        let region = f[8];
        let airport = if code == 4 { f[9] } else { "" };
        let name: String = f.get(10).map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| ident.to_string());

        stmt.execute(params![ident, name, navtype, lat, lon, elev,
                              raw_freq * freq_scale, mag_var, range_nm, region, airport])?;
    }
    drop(stmt);
    conn.execute_batch("COMMIT")?;
    Ok(())
}

// earth_fix.dat: lat lon ident region type_flags
pub fn parse_xplane_fix_dat(conn: &Connection, path: &Path) -> Result<()> {
    let content = std::fs::read_to_string(path)
        .with_context(|| format!("Cannot read {}", path.display()))?;

    conn.execute_batch("BEGIN")?;
    let mut stmt = conn.prepare(
        "INSERT INTO fixes (identifier,latitude,longitude,icaoRegion)
         VALUES (?1,?2,?3,?4)",
    )?;

    for line in content.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('I') || line.starts_with('A') { continue; }
        if line == "99" { break; }
        let f: Vec<&str> = line.split_whitespace().collect();
        if f.len() < 4 { continue; }
        let lat: f64 = f[0].parse().unwrap_or(0.0);
        let lon: f64 = f[1].parse().unwrap_or(0.0);
        stmt.execute(params![f[2], lat, lon, f[3]])?;
    }
    drop(stmt);
    conn.execute_batch("COMMIT")?;
    Ok(())
}

// earth_awy.dat:
//   FIX_FROM REG_FROM TYPE_FROM FIX_TO REG_TO TYPE_TO DIR LEVEL ALT_LO ALT_HI IDENT
pub fn parse_xplane_awy_dat(conn: &Connection, path: &Path) -> Result<()> {
    let content = std::fs::read_to_string(path)
        .with_context(|| format!("Cannot read {}", path.display()))?;

    conn.execute_batch("BEGIN")?;

    let mut airway_ids: std::collections::HashMap<String, i64> = std::collections::HashMap::new();
    let mut awy_stmt = conn.prepare(
        "INSERT OR IGNORE INTO airways (identifier, type) VALUES (?1,?2)",
    )?;
    let mut seg_stmt = conn.prepare(
        "INSERT INTO airway_segments
         (airwayId,fixFromId,fixToId,level,direction,minAltFt,maxAltFt)
         VALUES (?1,?2,?3,?4,?5,?6,?7)",
    )?;

    for line in content.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('I') || line.starts_with('A') { continue; }
        if line == "99" { break; }
        let f: Vec<&str> = line.split_whitespace().collect();
        if f.len() < 11 { continue; }

        let fix_from = f[0]; let reg_from = f[1];
        let fix_to   = f[3]; let reg_to   = f[4];
        let direction = f[6]; let level = f[7];
        let alt_lo: i64 = f[8].parse().unwrap_or(0);
        let alt_hi: i64 = f[9].parse().unwrap_or(99000);
        let ident = f[10];

        let awy_type = match level { "L" => "V", "H" => "J", _ => "Q" };
        let awy_id = if let Some(&id) = airway_ids.get(ident) {
            id
        } else {
            awy_stmt.execute(params![ident, awy_type])?;
            let id = conn.last_insert_rowid();
            airway_ids.insert(ident.to_string(), id);
            id
        };

        let from_id = resolve_fix(conn, fix_from, reg_from);
        let to_id   = resolve_fix(conn, fix_to, reg_to);
        let (from_id, to_id) = match (from_id, to_id) { (Some(a), Some(b)) => (a, b), _ => continue };

        let dir_code = match direction { "F" => "F", "B" => "B", _ => "N" };
        seg_stmt.execute(params![awy_id, from_id, to_id, level, dir_code, alt_lo, alt_hi])?;
    }

    drop(awy_stmt);
    drop(seg_stmt);
    conn.execute_batch("COMMIT")?;
    Ok(())
}

fn resolve_fix(conn: &Connection, ident: &str, region: &str) -> Option<i64> {
    conn.query_row(
        "SELECT id FROM fixes WHERE identifier=?1 AND icaoRegion=?2 LIMIT 1",
        params![ident, region],
        |r| r.get::<_, i64>(0),
    ).ok()
}

// ---------------------------------------------------------------------------
// OpenAIP XML parsers
// ---------------------------------------------------------------------------

pub fn parse_openaip_airspaces(conn: &Connection, dir: &Path) -> Result<()> {
    if !dir.exists() { return Ok(()); }
    for entry in std::fs::read_dir(dir)? {
        let path = entry?.path();
        if path.extension().and_then(|e| e.to_str()) == Some("xml") {
            if let Err(e) = parse_openaip_airspace_file(conn, &path) {
                eprintln!("  Warning: {}: {e}", path.display());
            }
        }
    }
    Ok(())
}

fn parse_openaip_airspace_file(conn: &Connection, path: &Path) -> Result<()> {
    use quick_xml::{Reader, events::Event};

    let content = std::fs::read_to_string(path)?;
    let mut reader = Reader::from_str(&content);
    reader.config_mut().trim_text(true);
    let mut buf = Vec::new();

    let mut in_asp = false;
    let mut cur_tag = String::new();
    let mut in_lower = false;
    let mut in_upper = false;
    // Per-airspace fields
    let mut name = String::new();
    let mut category = String::new();
    let mut country = String::new();
    let mut polygon = String::new();
    let mut floor_ft = 0i64;
    let mut ceiling_ft = 99_000i64;
    let mut floor_ref = "MSL".to_string();
    let mut ceiling_ref = "MSL".to_string();

    let mut stmt = conn.prepare(
        "INSERT INTO airspaces
         (name,airspaceClass,floorFt,ceilingFt,floorRef,ceilingRef,
          countryCode,geometryJson,bboxLatMin,bboxLatMax,bboxLonMin,bboxLonMax)
         VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12)",
    )?;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(e) => {
                let tag = String::from_utf8_lossy(e.local_name().as_ref()).to_string();
                if tag == "ASP" || tag == "Airspace" {
                    in_asp = true;
                    name.clear(); category.clear(); country.clear(); polygon.clear();
                    floor_ft = 0; ceiling_ft = 99_000;
                    floor_ref = "MSL".to_string(); ceiling_ref = "MSL".to_string();
                    for attr in e.attributes().flatten() {
                        let key = String::from_utf8_lossy(attr.key.as_ref()).to_uppercase();
                        if key == "CATEGORY" {
                            category = String::from_utf8_lossy(&attr.value).to_uppercase();
                        }
                    }
                } else if in_asp {
                    match tag.as_str() {
                        "ALTLIMIT_BOTTOM" | "LowerAltitude" => {
                            in_lower = true; in_upper = false;
                            for attr in e.attributes().flatten() {
                                if String::from_utf8_lossy(attr.key.as_ref()).to_uppercase() == "REFERENCE" {
                                    floor_ref = parse_alt_ref(&String::from_utf8_lossy(&attr.value));
                                }
                            }
                        }
                        "ALTLIMIT_TOP" | "UpperAltitude" => {
                            in_upper = true; in_lower = false;
                            for attr in e.attributes().flatten() {
                                if String::from_utf8_lossy(attr.key.as_ref()).to_uppercase() == "REFERENCE" {
                                    ceiling_ref = parse_alt_ref(&String::from_utf8_lossy(&attr.value));
                                }
                            }
                        }
                        _ => {}
                    }
                    cur_tag = tag;
                }
            }
            Event::Text(e) => {
                if !in_asp { continue; }
                let text = e.unescape().unwrap_or_default().to_string();
                match cur_tag.as_str() {
                    "NAME" | "Name"         => name = text,
                    "CATEGORY" | "Category" => if category.is_empty() { category = text.to_uppercase(); }
                    "COUNTRY" | "Country"   => country = text,
                    "POLYGON" | "Polygon"   => polygon = text,
                    "ALT" | "Altitude"      => {
                        let v: f64 = text.trim().parse().unwrap_or(0.0);
                        if in_lower { floor_ft = v as i64; }
                        else if in_upper { ceiling_ft = v as i64; }
                    }
                    _ => {}
                }
            }
            Event::End(e) => {
                let tag = String::from_utf8_lossy(e.local_name().as_ref()).to_string();
                if tag == "ALTLIMIT_BOTTOM" || tag == "LowerAltitude" { in_lower = false; }
                if tag == "ALTLIMIT_TOP"    || tag == "UpperAltitude" { in_upper = false; }
                if (tag == "ASP" || tag == "Airspace") && in_asp && !polygon.is_empty() {
                    let (json, lat_min, lat_max, lon_min, lon_max) = polygon_to_geojson(&polygon);
                    if lat_min < lat_max {
                        stmt.execute(params![
                            name, category, floor_ft, ceiling_ft,
                            floor_ref, ceiling_ref, country,
                            json, lat_min, lat_max, lon_min, lon_max
                        ])?;
                    }
                    in_asp = false;
                }
                cur_tag.clear();
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }
    Ok(())
}

fn parse_alt_ref(s: &str) -> String {
    match s.to_uppercase().as_str() {
        "STD" | "FL"  => "FL",
        "AGL" | "GND" => "AGL",
        _             => "MSL",
    }.to_string()
}

// Convert OpenAIP polygon string "lon,lat lon,lat …" → GeoJSON + bbox.
pub fn polygon_to_geojson(poly_str: &str) -> (String, f64, f64, f64, f64) {
    let mut coords: Vec<[f64; 2]> = Vec::new();
    for pair in poly_str.split_whitespace() {
        let mut parts = pair.splitn(2, ',');
        if let (Some(lon_s), Some(lat_s)) = (parts.next(), parts.next()) {
            let lon: f64 = lon_s.parse().unwrap_or(0.0);
            let lat: f64 = lat_s.parse().unwrap_or(0.0);
            coords.push([lon, lat]);
        }
    }
    if coords.is_empty() {
        return ("{}".to_string(), 0.0, 0.0, 0.0, 0.0);
    }
    let lat_min = coords.iter().map(|c| c[1]).fold(f64::MAX, f64::min);
    let lat_max = coords.iter().map(|c| c[1]).fold(f64::MIN, f64::max);
    let lon_min = coords.iter().map(|c| c[0]).fold(f64::MAX, f64::min);
    let lon_max = coords.iter().map(|c| c[0]).fold(f64::MIN, f64::max);
    let pts: Vec<String> = coords.iter().map(|c| format!("[{},{}]", c[0], c[1])).collect();
    let json = format!(r#"{{"type":"Polygon","coordinates":[[{}]]}}"#, pts.join(","));
    (json, lat_min, lat_max, lon_min, lon_max)
}

pub fn parse_openaip_obstacles(conn: &Connection, dir: &Path) -> Result<()> {
    if !dir.exists() { return Ok(()); }
    for entry in std::fs::read_dir(dir)? {
        let path = entry?.path();
        if path.extension().and_then(|e| e.to_str()) == Some("xml") {
            if let Err(e) = parse_openaip_obstacle_file(conn, &path) {
                eprintln!("  Warning: {}: {e}", path.display());
            }
        }
    }
    Ok(())
}

fn parse_openaip_obstacle_file(conn: &Connection, path: &Path) -> Result<()> {
    use quick_xml::{Reader, events::Event};

    let content = std::fs::read_to_string(path)?;
    let mut reader = Reader::from_str(&content);
    reader.config_mut().trim_text(true);
    let mut buf = Vec::new();

    let mut in_obs = false;
    let mut cur_tag = String::new();
    let mut obs_type = String::new();
    let mut lat = 0.0f64;
    let mut lon = 0.0f64;
    let mut height_agl = 0i64;
    let mut elev_msl   = 0i64;
    let mut country = String::new();

    let mut stmt = conn.prepare(
        "INSERT INTO obstacles
         (type,latitude,longitude,heightAglFt,elevationMslFt,source,countryCode)
         VALUES (?1,?2,?3,?4,?5,'openaip',?6)",
    )?;

    loop {
        match reader.read_event_into(&mut buf)? {
            Event::Start(e) => {
                let tag = String::from_utf8_lossy(e.local_name().as_ref()).to_string();
                if tag == "OBS" || tag == "Obstacle" {
                    in_obs = true;
                    obs_type.clear(); country.clear();
                    lat = 0.0; lon = 0.0; height_agl = 0; elev_msl = 0;
                }
                if in_obs { cur_tag = tag; }
            }
            Event::Text(e) => {
                if !in_obs { continue; }
                let text = e.unescape().unwrap_or_default().to_string();
                match cur_tag.as_str() {
                    "TYPE"  | "Type"      => obs_type = normalise_obstacle_type(&text),
                    "LAT"   | "Latitude"  => lat        = text.trim().parse().unwrap_or(0.0),
                    "LON"   | "Longitude" => lon        = text.trim().parse().unwrap_or(0.0),
                    "COUNTRY" | "Country" => country    = text,
                    "HEIGHTAGL" | "HeightAGL" => height_agl = text.trim().parse().unwrap_or(0),
                    "ELEV"  | "Elevation" => elev_msl   = text.trim().parse().unwrap_or(0),
                    _ => {}
                }
            }
            Event::End(e) => {
                let tag = String::from_utf8_lossy(e.local_name().as_ref()).to_string();
                if (tag == "OBS" || tag == "Obstacle") && in_obs {
                    if lat != 0.0 || lon != 0.0 {
                        stmt.execute(params![obs_type, lat, lon, height_agl, elev_msl, country])?;
                    }
                    in_obs = false;
                }
                cur_tag.clear();
            }
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }
    Ok(())
}

fn normalise_obstacle_type(raw: &str) -> String {
    match raw.to_uppercase().as_str() {
        "TOWER" | "LATTICE_TOWER"        => "tower",
        "ANTENNA" | "MAST"               => "antenna",
        "WIND_TURBINE" | "WINDTURBINE"   => "wind_turbine",
        "POWER_LINE" | "POWERLINE"       => "power_line",
        "CHIMNEY"                        => "chimney",
        _                                => "tower",
    }.to_string()
}

// ---------------------------------------------------------------------------
// R-tree population
// ---------------------------------------------------------------------------

pub fn populate_rtree_indexes(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        "INSERT OR IGNORE INTO airports_rtree (id, lat_min, lat_max, lon_min, lon_max)
         SELECT rowid, latitude, latitude, longitude, longitude FROM airports;
         INSERT OR IGNORE INTO navaids_rtree (id, lat_min, lat_max, lon_min, lon_max)
         SELECT id, latitude, latitude, longitude, longitude FROM navaids;"
    )?;
    Ok(())
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;

    /// In-memory DB with migrations applied and known SA data inserted directly.
    fn build_test_db() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        run_migrations(&conn).unwrap();

        let airports = [
            ("FAOR", "O.R. Tambo International Airport", -26.1392_f64, 28.2460_f64, 5558_i64, "large_airport",  "ZA", "Johannesburg"),
            ("FACT", "Cape Town International Airport",  -33.9649,     18.6017,      151,      "large_airport",  "ZA", "Cape Town"),
            ("FALA", "Lanseria International Airport",   -25.9385,     27.9261,     4517,      "large_airport",  "ZA", "Lanseria"),
            ("FALE", "King Shaka International Airport", -29.6145,     31.1197,      295,      "large_airport",  "ZA", "Durban"),
            ("FAWB", "Wonderboom Airport",               -25.6539,     28.2242,     4095,      "medium_airport", "ZA", "Pretoria"),
        ];

        for (icao, name, lat, lon, elev, atype, country, muni) in &airports {
            conn.execute(
                "INSERT INTO airports
                 (icao,name,latitude,longitude,elevationFt,airportType,
                  isTowered,isMilitary,countryCode,municipality,source)
                 VALUES (?1,?2,?3,?4,?5,?6,1,0,?7,?8,'test')",
                params![icao, name, lat, lon, elev, atype, country, muni],
            ).unwrap();
            let rowid = conn.last_insert_rowid();
            conn.execute(
                "INSERT INTO airports_rtree (id,lat_min,lat_max,lon_min,lon_max)
                 VALUES (?1,?2,?2,?3,?3)",
                params![rowid, lat, lon],
            ).unwrap();
        }

        let navaids = [
            ("ORT", "OR Tambo VOR",        "VOR", -26.1392_f64, 28.2460_f64, 113_000_000_i64, "FA", "FAOR"),
            ("JHB", "Johannesburg VOR-DME","VOR", -26.1600,      28.0000,     114_000_000,     "FA", ""),
        ];
        for (ident, name, ntype, lat, lon, freq_hz, region, airport) in &navaids {
            conn.execute(
                "INSERT INTO navaids
                 (identifier,name,type,latitude,longitude,elevationFt,
                  frequencyHz,magneticVariation,rangeNm,icaoRegion,airportIcao)
                 VALUES (?1,?2,?3,?4,?5,5558,?6,0.0,130,?7,?8)",
                params![ident, name, ntype, lat, lon, freq_hz, region, airport],
            ).unwrap();
            let nav_id = conn.last_insert_rowid();
            conn.execute(
                "INSERT INTO navaids_rtree (id,lat_min,lat_max,lon_min,lon_max)
                 VALUES (?1,?2,?2,?3,?3)",
                params![nav_id, lat, lon],
            ).unwrap();
        }

        conn
    }

    #[test]
    fn sa_airports_present() {
        let conn = build_test_db();
        for icao in ["FAOR", "FACT", "FALA", "FALE", "FAWB"] {
            let count: i64 = conn.query_row(
                "SELECT COUNT(*) FROM airports WHERE icao=?1",
                params![icao], |r| r.get(0),
            ).unwrap();
            assert_eq!(count, 1, "Missing airport {icao}");
        }
    }

    #[test]
    fn sa_vor_navaids_present() {
        let conn = build_test_db();
        for ident in ["ORT", "JHB"] {
            let count: i64 = conn.query_row(
                "SELECT COUNT(*) FROM navaids WHERE identifier=?1 AND type='VOR'",
                params![ident], |r| r.get(0),
            ).unwrap();
            assert!(count > 0, "Missing VOR {ident}");
        }
    }

    #[test]
    fn rtree_query_returns_sa_airports() {
        let conn = build_test_db();
        // Bounding box covering Johannesburg area
        let results: Vec<String> = conn
            .prepare(
                "SELECT a.icao FROM airports a
                 JOIN airports_rtree r ON r.id = a.rowid
                 WHERE r.lat_min >= ?1 AND r.lat_max <= ?2
                   AND r.lon_min >= ?3 AND r.lon_max <= ?4",
            ).unwrap()
            .query_map(params![-27.5_f64, -25.0_f64, 27.0_f64, 29.5_f64], |r| r.get(0))
            .unwrap()
            .filter_map(|r| r.ok())
            .collect();

        assert!(results.contains(&"FAOR".to_string()), "FAOR not in Joburg bbox");
        assert!(results.contains(&"FAWB".to_string()), "FAWB not in Joburg bbox");
        assert!(!results.contains(&"FACT".to_string()), "FACT should not be in Joburg bbox");
    }

    #[test]
    fn procedures_table_is_empty_in_fresh_db() {
        let conn = build_test_db();
        let count: i64 = conn.query_row(
            "SELECT COUNT(*) FROM procedures", [], |r| r.get(0),
        ).unwrap();
        assert_eq!(count, 0);
    }

    #[test]
    fn polygon_to_geojson_correct_bbox() {
        // lon,lat pairs — unit box around SA
        let poly = "28.0,-26.0 29.0,-26.0 29.0,-25.0 28.0,-25.0 28.0,-26.0";
        let (json, lat_min, lat_max, lon_min, lon_max) = polygon_to_geojson(poly);
        assert!((lat_min - (-26.0)).abs() < 1e-9);
        assert!((lat_max - (-25.0)).abs() < 1e-9);
        assert!((lon_min - 28.0).abs() < 1e-9);
        assert!((lon_max - 29.0).abs() < 1e-9);
        assert!(json.contains(r#""type":"Polygon""#));
    }
}
