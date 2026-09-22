//! Live probe of the Azure-app (browser + loopback) auth flow: same client
//! ID, port, scopes the launcher uses. Decodes the MSA token's `scp` claim
//! (does it actually carry XboxLive.signin?) and prints how far the chain
//! gets (MSA → XBL → XSTS → MCS → entitlements), so a missing consent grant
//! is distinguishable from an Xbox allow-list block. Not part of the test
//! suite: run manually with `cargo run -p fasterlauncher-core --example
//! azure_live` and complete the sign-in in the browser it opens.

use fasterlauncher_core::auth::{
    authorize_url_with_prompt, exchange_code, full_login, has_entitlements, new_flow_state,
    parse_auth_callback, AuthConfig, AuthMode,
};
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

const CLIENT_ID: &str = "0e36efd3-4bb6-4bee-ac76-d33ac47fd3df";
const PORT: u16 = 19735;

#[tokio::main]
async fn main() {
    match run().await {
        Ok(()) => {}
        Err(e) => {
            eprintln!("FAIL: {e}");
            std::process::exit(1);
        }
    }
}

async fn run() -> Result<(), String> {
    let config = AuthConfig {
        client_id: CLIENT_ID.into(),
        redirect_uri: format!("http://127.0.0.1:{PORT}"),
        mode: AuthMode::AzureApp,
    };
    config.validate().map_err(|e| e.to_string())?;

    let listener = tokio::net::TcpListener::bind(("127.0.0.1", PORT))
        .await
        .map_err(|e| format!("bind 127.0.0.1:{PORT}: {e}"))?;
    let state = new_flow_state();
    let url = authorize_url_with_prompt(&config, &state, "consent").map_err(|e| e.to_string())?;
    println!("authorize URL ok, opening browser…\n  {url}");
    let _ = std::process::Command::new("open").arg(&url).status();

    let code = wait_for_code(listener, &state).await?;
    println!("[1/4] MSA: browser callback captured, exchanging code…");
    let (ms_token, refresh) = exchange_code(&reqwest::Client::new(), &config, &code)
        .await
        .map_err(|e| format!("MSA token exchange failed: {e}"))?;
    println!("[2/4] MSA: tokens ok (refresh token issued)");
    print_token_claims(&ms_token);

    println!("[3/4] XBL → XSTS → MCS (this is where an allow-list block would fire)…");
    let session = full_login(&reqwest::Client::new(), refresh, &ms_token, config.mode)
        .await
        .map_err(|e| format!("Xbox/Minecraft chain failed: {e}"))?;
    println!("[3/4] Xbox/Minecraft chain ok: {} ({})", session.username, session.uuid);

    match has_entitlements(&reqwest::Client::new(), &session.access_token).await {
        Ok(true) => println!("[4/4] entitlements: owns Minecraft: Java Edition"),
        Ok(false) => println!("[4/4] entitlements: account has NO Minecraft (auth pipeline itself works)"),
        Err(e) => println!("[4/4] entitlements check errored (auth pipeline itself works): {e}"),
    }
    println!("RESULT: allow-listed, non-code flow WORKS end-to-end");
    Ok(())
}

async fn wait_for_code(
    listener: tokio::net::TcpListener,
    expected_state: &str,
) -> Result<String, String> {
    let deadline = tokio::time::Instant::now() + Duration::from_secs(540);
    loop {
        let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
        if remaining.is_zero() {
            return Err("timed out waiting for the browser (nobody signed in within 9 min)".into());
        }
        let (mut stream, _) = tokio::time::timeout(remaining, listener.accept())
            .await
            .map_err(|_| "timed out waiting for the browser".to_string())?
            .map_err(|e| format!("accept: {e}"))?;
        let mut buf = vec![0u8; 8192];
        let n = tokio::time::timeout(Duration::from_secs(30), stream.read(&mut buf))
            .await
            .map_err(|_| "timed out reading request".to_string())?
            .map_err(|e| format!("read: {e}"))?;
        let req = String::from_utf8_lossy(&buf[..n]);
        let target = req
            .lines()
            .next()
            .and_then(|l| l.split_whitespace().nth(1))
            .unwrap_or("/");
        if !target.contains('?') {
            let _ = respond(&mut stream).await;
            continue;
        }
        let outcome = parse_auth_callback(target, expected_state).map(|code| (code, true));
        let _ = respond(&mut stream).await;
        return match outcome {
            Ok((code, _)) => Ok(code),
            Err(e) => Err(e.to_string()),
        };
    }
}

/// Decode the JWT payload and print the claims that decide between the two
/// Xbox-400 causes: `scp` (was XboxLive.signin actually granted?) and `aud`.
fn print_token_claims(token: &str) {
    use base64::Engine;
    let payload_b64 = match token.split('.').nth(1) {
        Some(p) => p,
        None => {
            println!("      token: not a JWT (no payload segment)");
            return;
        }
    };
    let bytes = match base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(payload_b64) {
        Ok(b) => b,
        Err(e) => {
            println!("      token: payload not base64url ({e})");
            return;
        }
    };
    let claims: serde_json::Value = match serde_json::from_slice(&bytes) {
        Ok(c) => c,
        Err(e) => {
            println!("      token: payload not JSON ({e})");
            return;
        }
    };
    let scp = claims.get("scp").and_then(|s| s.as_str()).unwrap_or("(none)");
    println!("      token scp: {scp}");
    println!("      token aud: {}", claims.get("aud").and_then(|a| a.as_str()).unwrap_or("(none)"));
    if scp.contains("XboxLive.signin") {
        println!("      → grant IS present; a 400 from Xbox now points at the allow-list");
    } else {
        println!("      → grant MISSING; this alone explains the Xbox 400");
    }
}

async fn respond(stream: &mut tokio::net::TcpStream) -> std::io::Result<()> {
    let page = "<html><body style=\"background:#141414;color:#e8e4da;font-family:monospace;padding:40px\"><h2>Probe sign-in captured — return to the terminal.</h2></body></html>";
    let head = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        page.len()
    );
    stream.write_all(head.as_bytes()).await?;
    stream.write_all(page.as_bytes()).await?;
    stream.flush().await
}
