//! Runs Hydro's in-process stub RAP tool servers (`rap-test-servers`, the ones their TUI/web
//! e2e suites use) as a standalone process so Protoweft's client can be pointed at them.
//!
//! Prints one JSON line `{"image_port": n, "diff_port": m}` on stdout once both listen, then
//! serves until SIGINT or SIGTERM.

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let image_port = rap_test_servers::start_stub_image_server().await?;
    let diff_port = rap_test_servers::start_stub_diff_server().await?;
    println!(
        "{}",
        serde_json::json!({ "image_port": image_port, "diff_port": diff_port })
    );

    let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = term.recv() => {}
    }
    Ok(())
}
