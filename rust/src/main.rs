//! `vgi-starter-worker`: serve the starter catalog.
//!
//! No arguments serves over stdio (what DuckDB launches). `--http --port 0
//! [--host H]` serves HTTP and prints `PORT:<n>` on stdout once listening;
//! `--unix PATH` serves a Unix socket. `--debug` (or `RUST_LOG=debug`) turns on
//! debug logging. Logs always go to stderr: stdout carries the protocol.

fn main() {
    let debug = std::env::args().any(|a| a == "--debug");
    let mut logger =
        env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("warn"));
    if debug {
        logger.filter_level(log::LevelFilter::Debug);
    }
    logger.target(env_logger::Target::Stderr).init();
    starter_worker::build_worker().run();
}
