//! Desktop test harness for the bridge: runs a JSON script of commands through the same
//! `execute` the app uses, and prints each reply.
//!
//!   cadhost run script.json     [{"command": "...", "params": {...}}, ...]
//!   cadhost exec <command> '<json>'
//!   cadhost scene               sizes of the scene buffer after the script

use serde_json::Value;

fn main() {
    let a: Vec<String> = std::env::args().collect();
    match a.get(1).map(String::as_str) {
        Some("exec") => {
            println!("{}", cadbridge::execute(&a[2], a.get(3).map_or("{}", String::as_str)));
        }
        Some("run") => {
            let txt = std::fs::read_to_string(&a[2]).expect("script");
            let steps: Vec<Value> = serde_json::from_str(&txt).expect("json");
            let quiet = a.iter().any(|x| x == "-q");
            let mut failed = 0;
            for (i, st) in steps.iter().enumerate() {
                let cmd = st["command"].as_str().unwrap_or("");
                if cmd == "@pick" {
                    let o: Vec<f64> = serde_json::from_value(st["o"].clone()).unwrap();
                    let d: Vec<f64> = serde_json::from_value(st["d"].clone()).unwrap();
                    let _ = cadbridge::scene();
                    println!("{i} @pick -> {}", cadbridge::pick([o[0], o[1], o[2]], [d[0], d[1], d[2]], 0.0, 0.02));
                    continue;
                }
                if cmd == "@curves" {
                    let c = cadbridge::sketch_curves();
                    println!("{i} @curves -> {} bytes {}", c.len(), c.chars().take(160).collect::<String>());
                    continue;
                }
                if cmd == "@scenefile" {
                    std::fs::write(st["path"].as_str().unwrap(), cadbridge::scene()).unwrap();
                    continue;
                }
                if cmd == "@scene" {
                    println!("{i} @scene -> {} bytes", cadbridge::scene().len());
                    continue;
                }
                let r = cadbridge::execute(cmd, &st.get("params").cloned().unwrap_or(Value::Null).to_string().replace("null", "{}"));
                let ok = r.starts_with("{\"ok\":true");
                if !ok {
                    failed += 1;
                }
                let max = st.get("show").and_then(Value::as_u64).unwrap_or(if quiet && ok { 0 } else { 400 }) as usize;
                let shown: String = r.chars().take(max).collect();
                println!("{i} {cmd} -> {}{}", if ok { "ok " } else { "FAILED " }, shown);
            }
            println!("status {}", cadbridge::status());
            std::process::exit(if failed > 0 { 1 } else { 0 });
        }
        _ => eprintln!("usage: cadhost run <script.json> [-q] | exec <command> <json>"),
    }
}
