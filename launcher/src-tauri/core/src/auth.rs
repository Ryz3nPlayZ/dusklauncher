//! Microsoft OAuth → Xbox Live → XSTS → Minecraft services authentication.
//!
//! Reference: https://minecraft.wiki/w/Microsoft_authentication
//!
//! Chain: auth-code (+loopback redirect) → MSA tokens → XBL → XSTS →
//! minecraftservices login → entitlements check → profile. The refresh token
//! gives silent re-login; see `refresh_session`.
//!
//! Two sign-in identities ship: the official Minecraft title ID on the
//! legacy login.live.com endpoints (default; needs no Azure app and no
//! Microsoft approval) and DuskLauncher's own Azure app on the v2
//! `/consumers` endpoints (needs Microsoft AppID approval — otherwise Xbox
//! rejects the token with an opaque HTTP 400). See [`AuthMode`].

use crate::{Error, Result};
use base64::Engine as _;
use rand::Rng;
use serde::{Deserialize, Serialize};

pub const MSA_AUTHORIZE_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
pub const MSA_TOKEN_URL: &str = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
pub const XBL_AUTH_URL: &str = "https://user.auth.xboxlive.com/user/authenticate";
pub const XSTS_AUTH_URL: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
pub const XBL_DEVICE_AUTH_URL: &str = "https://device.auth.xboxlive.com/device/authenticate";
pub const SISU_AUTH_URL: &str = "https://sisu.xboxlive.com/authorize";
pub const MCS_LOGIN_URL: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
pub const MCS_ENTITLEMENTS_URL: &str = "https://api.minecraftservices.com/entitlements/mcstore";
pub const MCS_PROFILE_URL: &str = "https://api.minecraftservices.com/minecraft/profile";
pub const MCS_SKINS_URL: &str = "https://api.minecraftservices.com/minecraft/profile/skins";

/// Scopes for the initial Microsoft authorization request (Azure v2 flow).
pub const MSA_SCOPES: &str = "XboxLive.signin offline_access";

/// Legacy MSA endpoints (login.live.com). Title-ID clients authenticate here,
/// not on the Azure v2 endpoints.
pub const LIVE_AUTHORIZE_URL: &str = "https://login.live.com/oauth20_authorize.srf";
pub const LIVE_TOKEN_URL: &str = "https://login.live.com/oauth20_token.srf";
pub const LIVE_DEVICE_CODE_URL: &str = "https://login.live.com/oauth20_connect.srf";
/// Official Minecraft: Java Edition launcher's Xbox title ID (Win32). Public
/// identifier of the first-party title — signing in as this title needs no
/// Azure app and no Microsoft AppID approval, which is why it is the default
/// auth mode. Same approach as RaphiMC's MinecraftAuth and the wider tool
/// ecosystem.
pub const OFFICIAL_TITLE_CLIENT_ID: &str = "00000000402b5328";
/// Title-auth scope for the official Minecraft title ID — exactly what
/// MinecraftAuth pairs with `JAVA_TITLE_ID`. It yields an RPS *title ticket*,
/// which `user.auth.xboxlive.com` only accepts with the `t=` prefix.
/// Requesting `XboxLive.signin` instead yields a `d=`-style token, and mixing
/// the scope and prefix is an instant Xbox 400. The v1 endpoint issues
/// refresh tokens implicitly for public clients, so no offline_access-style
/// scope is requested.
pub const LIVE_SCOPES: &str = "service::user.auth.xboxlive.com::MBI_SSL";

/// Which Microsoft identity the launcher signs in with.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuthMode {
    /// DuskLauncher's own Azure app via the v2 `/consumers` endpoints.
    /// Requires Microsoft's AppID approval (https://aka.ms/mce-reviewappid);
    /// unapproved IDs are rejected by Xbox with an opaque HTTP 400.
    AzureApp,
    /// The official Minecraft launcher's Xbox title ID via legacy login.live.com
    /// endpoints. Works with zero setup and zero approval.
    OfficialTitle,
}

impl AuthMode {
    /// Parse the persisted `authMode` setting. Unknown values fall back to the
    /// Azure flow so a corrupted setting can't silently change identity.
    pub fn from_setting(s: &str) -> Self {
        match s.trim().to_ascii_lowercase().as_str() {
            "official" => Self::OfficialTitle,
            _ => Self::AzureApp,
        }
    }

    pub fn authorize_url(self) -> &'static str {
        match self {
            Self::AzureApp => MSA_AUTHORIZE_URL,
            Self::OfficialTitle => LIVE_AUTHORIZE_URL,
        }
    }

    pub fn token_url(self) -> &'static str {
        match self {
            Self::AzureApp => MSA_TOKEN_URL,
            Self::OfficialTitle => LIVE_TOKEN_URL,
        }
    }

    pub fn scopes(self) -> &'static str {
        match self {
            Self::AzureApp => MSA_SCOPES,
            Self::OfficialTitle => LIVE_SCOPES,
        }
    }
}

/// Validate an Azure app client_id before opening the browser. A bad ID
/// otherwise fails three hops later as an opaque Xbox 400, which is exactly
/// the dead end this guards against.
pub fn validate_client_id(client_id: &str) -> Result<()> {
    let id = client_id.trim();
    if id.is_empty() {
        return Err(Error::Auth(
            "No Azure client ID is configured — set one in Settings → Microsoft sign-in, or reset it to the shipped default, then retry.".into(),
        ));
    }
    // GUID shape: 8-4-4-4-12 lowercase/uppercase hex.
    let parts: Vec<&str> = id.split('-').collect();
    let lens = [8usize, 4, 4, 4, 12];
    let shape_ok = parts.len() == 5
        && parts.iter().zip(lens).all(|(p, n)| {
            p.len() == n && p.bytes().all(|b| b.is_ascii_hexdigit())
        });
    if !shape_ok {
        return Err(Error::Auth(
            "The Auth Client ID in Settings does not look like an Azure app ID (expected a GUID like 00000000-0000-0000-0000-000000000000) — fix it or clear it to use the shipped default, then retry.".into(),
        ));
    }
    Ok(())
}

/// Clock skew subtracted from token expiry before treating a session as stale.
const EXPIRY_SKEW_MILLIS: u64 = 60_000;

#[derive(Debug, Clone)]
pub struct AuthConfig {
    pub client_id: String,
    /// Loopback redirect URI, e.g. http://127.0.0.1:19735
    pub redirect_uri: String,
    /// Which Microsoft identity `client_id` refers to. In `OfficialTitle`
    /// mode the client_id is expected to be [`OFFICIAL_TITLE_CLIENT_ID`] and
    /// the legacy live.com endpoints/scopes are used.
    pub mode: AuthMode,
}

impl AuthConfig {
    /// Mode-aware client-id validation: Azure GUIDs are shape-checked (a bad
    /// ID otherwise fails three hops later as an opaque Xbox 400); the
    /// official title ID is a fixed first-party identifier and skipped.
    pub fn validate(&self) -> Result<()> {
        match self.mode {
            AuthMode::AzureApp => validate_client_id(&self.client_id),
            AuthMode::OfficialTitle => Ok(()),
        }
    }
}

/// A device-code login session started at `login.live.com/oauth20_connect.srf`
/// (RFC 8628). The user visits `verification_uri` and types `user_code`;
/// meanwhile we poll the token endpoint until they finish.
#[derive(Debug, Clone, Deserialize)]
pub struct DeviceCodeStart {
    pub device_code: String,
    pub user_code: String,
    #[serde(rename = "verification_uri")]
    pub verification_uri: String,
    /// Same page with the code prefilled (`?otc=`). When present, opening
    /// this instead of `verification_uri` means the user just confirms —
    /// no code typing (what Prism does).
    #[serde(rename = "verification_uri_complete", default)]
    pub verification_uri_complete: Option<String>,
    /// Seconds between token polls (Microsoft asks for 5).
    #[serde(default = "default_poll_interval")]
    pub interval: u64,
    /// Seconds until the user_code expires.
    #[serde(default = "default_device_code_ttl")]
    pub expires_in: u64,
}

fn default_poll_interval() -> u64 {
    5
}

fn default_device_code_ttl() -> u64 {
    900
}

/// Kick off a device-code login. Official-title mode only: Azure v2 apps
/// would use their own devicecode endpoint, which we don't ship.
pub async fn start_device_code(
    client: &reqwest::Client,
    config: &AuthConfig,
) -> Result<DeviceCodeStart> {
    if config.mode != AuthMode::OfficialTitle {
        return Err(Error::Auth(
            "device-code sign-in is only available in Official mode".into(),
        ));
    }
    let resp = client
        .post(LIVE_DEVICE_CODE_URL)
        .form(&[
            ("client_id", config.client_id.as_str()),
            ("scope", config.mode.scopes()),
            ("response_type", "device_code"),
        ])
        .send()
        .await?;
    if !resp.status().is_success() {
        let status = resp.status().as_u16();
        let body = resp.text().await.unwrap_or_default();
        return Err(Error::Auth(map_msa_error(status, &body)));
    }
    Ok(resp.json().await?)
}

/// Interpret one device-code token poll. `Ok(None)` means "still pending,
/// keep polling"; errors are mapped like every other MSA failure.
fn device_poll_outcome(status: u16, body: &str) -> Result<Option<(String, String)>> {
    let parsed: serde_json::Value = serde_json::from_str(body).unwrap_or_default();
    let err = parsed.get("error").and_then(|e| e.as_str()).unwrap_or("");
    if status == 200 && err.is_empty() {
        let access = parsed
            .get("access_token")
            .and_then(|v| v.as_str())
            .ok_or_else(|| Error::Auth("device-code response had no access_token".into()))?;
        // The v1 endpoint issues refresh tokens implicitly; if one is absent
        // anyway, sign in with an empty one — the session just loses silent
        // re-login and the next launch asks for a fresh code.
        let refresh = parsed
            .get("refresh_token")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_owned();
        return Ok(Some((access.to_owned(), refresh)));
    }
    match err {
        "authorization_pending" | "slow_down" => Ok(None),
        _ => Err(Error::Auth(map_msa_error(status, body))),
    }
}

/// Poll the token endpoint until the user finishes the device-code login
/// (or the code expires). Mirrors MinecraftAuth's DeviceCodeMsaAuthService.
pub async fn poll_device_code(
    client: &reqwest::Client,
    config: &AuthConfig,
    start: &DeviceCodeStart,
) -> Result<(String, String)> {
    let deadline = now_millis() + start.expires_in.saturating_sub(5) * 1000;
    let mut interval = start.interval.max(1);
    loop {
        if now_millis() >= deadline {
            return Err(Error::Auth(
                "The sign-in code expired before you finished — start again.".into(),
            ));
        }
        let resp = client
            .post(config.mode.token_url())
            .form(&[
                ("client_id", config.client_id.as_str()),
                ("grant_type", "device_code"),
                ("device_code", start.device_code.as_str()),
            ])
            .send()
            .await?;
        let status = resp.status().as_u16();
        let body = resp.text().await.unwrap_or_default();
        match device_poll_outcome(status, &body)? {
            Some(tokens) => return Ok(tokens),
            None => {
                // slow_down asks for double the interval (RFC 8628 §3.5)
                let body_slow = body.contains("slow_down");
                if body_slow {
                    interval = (interval * 2).min(30);
                }
                tokio::time::sleep(std::time::Duration::from_secs(interval)).await;
            }
        }
    }
}

/// Windows FILETIME (100ns ticks since 1601-01-01) for a unix timestamp —
/// the timestamp format Xbox's proof-of-possession signatures use.
fn windows_filetime(unix_secs: u64) -> u64 {
    (unix_secs + 11_644_473_600) * 10_000_000
}

/// Byte layout Xbox expects to be ES256-signed for the `Signature` header
/// (mirrors MinecraftAuth's SignedXblPostRequest): policy version, NUL,
/// FILETIME, NUL, HTTP method, NUL, path+query, NUL, optional Authorization
/// header value, NUL, body, NUL.
fn xbox_signature_payload(
    filetime: u64,
    method: &str,
    path_query: &str,
    authorization: Option<&str>,
    body: &[u8],
) -> Vec<u8> {
    let mut buf = Vec::with_capacity(body.len() + 64);
    buf.extend_from_slice(&1u32.to_be_bytes());
    buf.push(0);
    buf.extend_from_slice(&filetime.to_be_bytes());
    buf.push(0);
    buf.extend_from_slice(method.as_bytes());
    buf.push(0);
    buf.extend_from_slice(path_query.as_bytes());
    buf.push(0);
    if let Some(auth) = authorization {
        buf.extend_from_slice(auth.as_bytes());
    }
    buf.push(0);
    buf.extend_from_slice(body);
    buf.push(0);
    buf
}

/// The `Signature` header value: policy version + FILETIME + 64-byte P1363
/// (r‖s) signature, standard-base64 encoded.
fn xbox_signature_header(filetime: u64, signature: &[u8; 64]) -> String {
    let mut buf = Vec::with_capacity(12 + 64);
    buf.extend_from_slice(&1u32.to_be_bytes());
    buf.extend_from_slice(&filetime.to_be_bytes());
    buf.extend_from_slice(signature);
    base64::engine::general_purpose::STANDARD.encode(buf)
}

/// JWK form of the P-256 public key Xbox calls `ProofKey`.
fn xbox_proof_key(verifying_key: &p256::ecdsa::VerifyingKey) -> serde_json::Value {
    let point = verifying_key.to_encoded_point(false);
    let bytes = point.as_bytes(); // 0x04 ‖ x ‖ y
    let enc = base64::engine::general_purpose::URL_SAFE_NO_PAD;
    serde_json::json!({
        "kty": "EC",
        "alg": "ES256",
        "crv": "P-256",
        "use": "sig",
        "x": enc.encode(&bytes[1..33]),
        "y": enc.encode(&bytes[33..65]),
    })
}

/// Random `{uuid}` for the device token's `Id` property.
fn random_device_id() -> String {
    let mut b = [0u8; 16];
    rand::thread_rng().fill(&mut b);
    b[6] = (b[6] & 0x0f) | 0x40; // version 4
    b[8] = (b[8] & 0x3f) | 0x80; // RFC 4122 variant
    let h = hex::encode(b);
    format!("{{{}-{}-{}-{}-{}}}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

/// POST to an Xbox endpoint with the proof-of-possession `Signature` header.
/// The signature covers the exact body bytes, so the body is serialized once
/// here and sent verbatim.
async fn signed_xbl_post(
    client: &reqwest::Client,
    url: &str,
    body: String,
    signing_key: &p256::ecdsa::SigningKey,
    contract_version: bool,
) -> Result<reqwest::Response> {
    let parsed = reqwest::Url::parse(url).map_err(|e| Error::Other(e.to_string()))?;
    let path_query = match parsed.query() {
        Some(q) => format!("{}{}", parsed.path(), q),
        None => parsed.path().to_string(),
    };
    let filetime = windows_filetime(now_millis() / 1000);
    let payload = xbox_signature_payload(filetime, "POST", &path_query, None, body.as_bytes());
    use p256::ecdsa::signature::Signer;
    let signature: p256::ecdsa::Signature = signing_key.sign(&payload);
    let sig_bytes = signature.to_bytes();
    let mut sig_arr = [0u8; 64];
    sig_arr.copy_from_slice(&sig_bytes);
    let mut req = client
        .post(url)
        .header(reqwest::header::ACCEPT, "application/json")
        .header(reqwest::header::CONTENT_TYPE, "application/json")
        .header("Signature", xbox_signature_header(filetime, &sig_arr))
        .body(body);
    if contract_version {
        req = req.header("X-Xbl-Contract-Version", "1");
    }
    Ok(req.send().await?)
}

/// Register a per-login "device" with Xbox: proof-of-possession via the ES256
/// key generated for this login. Returns the device token bound to that key.
async fn xbl_device_token(
    client: &reqwest::Client,
    signing_key: &p256::ecdsa::SigningKey,
    verifying_key: &p256::ecdsa::VerifyingKey,
) -> Result<String> {
    let device_body = serde_json::json!({
        "Properties": {
            "DeviceType": "Win32",
            "Id": random_device_id(),
            "AuthMethod": "ProofOfPossession",
            "ProofKey": xbox_proof_key(verifying_key),
        },
        "RelyingParty": "http://auth.xboxlive.com",
        "TokenType": "JWT",
    })
    .to_string();
    let device_resp = signed_xbl_post(client, XBL_DEVICE_AUTH_URL, device_body, signing_key, true).await?;
    if !device_resp.status().is_success() {
        let status = device_resp.status().as_u16();
        let body = device_resp.text().await.unwrap_or_default();
        return Err(Error::Auth(map_xbl_error(status, &body, AuthMode::OfficialTitle)));
    }
    let device: XblResponse = device_resp.json().await?;
    Ok(device.Token)
}

/// The SISU (single sign-on) exchange for title clients: one call returns the
/// user token, title token and the XSTS token for the relying party. This is
/// the path MinecraftAuth has shipped for title IDs since 4.1 —
/// `user/authenticate` no longer accepts title tickets (verified live: it
/// 400s for both `t=` and `d=` prefixes on an MBI_SSL device-code token).
async fn authenticate_title_sisu(
    client: &reqwest::Client,
    ms_access_token: &str,
) -> Result<(String, String)> {
    let secret = p256::SecretKey::random(&mut p256::elliptic_curve::rand_core::OsRng);
    let signing_key = p256::ecdsa::SigningKey::from(&secret);
    let verifying_key = p256::ecdsa::VerifyingKey::from(&signing_key);

    // 1. Register this "device" (per-login keypair; Xbox returns a device
    //    token bound to the ProofKey we sign both requests with).
    let device_token = xbl_device_token(client, &signing_key, &verifying_key).await?;

    // 2. Swap the MSA title ticket for user+XSTS tokens in one call. The
    //    MBI_SSL ticket is passed with the `t=` prefix here — the pairing
    //    that `user/authenticate` refuses.
    let sisu_body = serde_json::json!({
        "Sandbox": "RETAIL",
        "UseModernGamertag": true,
        "AppId": OFFICIAL_TITLE_CLIENT_ID,
        "AccessToken": format!("t={ms_access_token}"),
        "DeviceToken": device_token,
        "ProofKey": xbox_proof_key(&verifying_key),
        "RelyingParty": "rp://api.minecraftservices.com/",
    })
    .to_string();
    let sisu_resp = signed_xbl_post(client, SISU_AUTH_URL, sisu_body, &signing_key, false).await?;
    if !sisu_resp.status().is_success() {
        let status = sisu_resp.status().as_u16();
        let body = sisu_resp.text().await.unwrap_or_default();
        // SISU reports account problems as XErr codes like XSTS does.
        let msg = if body.contains("XErr") {
            map_xsts_error(status, &body)
        } else {
            map_xbl_error(status, &body, AuthMode::OfficialTitle)
        };
        return Err(Error::Auth(msg));
    }
    let sisu: SisuResponse = sisu_resp.json().await?;
    let uhs = xbl_uhs(&sisu.authorization_token)?;
    Ok((uhs, sisu.authorization_token.Token))
}

#[derive(Debug, Deserialize)]
struct SisuResponse {
    #[serde(rename = "AuthorizationToken")]
    authorization_token: XblResponse,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Session {
    pub access_token: String,

    /// Millis since epoch when the MC access token expires.
    pub expires_at: u64,
    pub uuid: String,
    pub username: String,
    /// Xbox user hash (`uhs`) — passed to the game as `auth_xuid`.
    #[serde(default)]
    pub xuid: String,
    /// Microsoft refresh token for silent re-login.
    pub refresh_token: String,
    /// URL of the account's active skin (textures.minecraft.net), refetched
    /// on login. Empty when the account has no custom skin.
    #[serde(default)]
    pub skin_url: String,
    /// "classic" or "slim" — arm model of the active skin.
    #[serde(default)]
    pub skin_variant: String,
}

impl Session {
    pub fn is_expired(&self, now_millis: u64) -> bool {
        now_millis + EXPIRY_SKEW_MILLIS >= self.expires_at
    }

    pub fn needs_refresh(&self) -> bool {
        self.is_expired(now_millis())
    }
}

#[derive(Debug, Deserialize)]
struct MsaTokenResponse {
    access_token: String,
    refresh_token: Option<String>,
    expires_in: u64,
}

#[derive(Debug, Deserialize)]
struct XblResponse {
    Token: String,
    #[serde(rename = "DisplayClaims")]
    display_claims: serde_json::Value,
}

#[derive(Debug, Deserialize)]
struct McsLoginResponse {
    access_token: String,
    expires_in: u64,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct ProfileResponse {
    pub id: String,
    pub name: String,
    #[serde(default)]
    pub skins: Vec<SkinEntry>,
    #[serde(default)]
    pub capes: Vec<CapeEntry>,
}

/// One entry of the profile's `skins` array; `state: "ACTIVE"` marks the
/// skin currently applied to the account.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct SkinEntry {
    pub id: String,
    pub state: String,
    pub url: String,
    #[serde(default)]
    pub variant: String,
    #[serde(default)]
    pub alias: Option<String>,
}

#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct CapeEntry {
    pub id: String,
    pub state: String,
    #[serde(default)]
    pub alias: Option<String>,
}

impl ProfileResponse {
    /// The currently applied skin, if the account has one.
    pub fn active_skin(&self) -> Option<&SkinEntry> {
        self.skins.iter().find(|s| s.state.eq_ignore_ascii_case("ACTIVE"))
    }
}

/// Result of the XBL → XSTS → minecraftservices exchange.
pub struct McAuth {
    pub access_token: String,
    pub xuid: String,
    pub expires_in: u64,
}

fn xbl_uhs(xbl: &XblResponse) -> Result<String> {
    xbl.display_claims
        .get("xui")
        .and_then(|v| v.as_array())
        .and_then(|a| a.first())
        .and_then(|c| c.get("uhs"))
        .and_then(|u| u.as_str())
        .map(str::to_owned)
        .ok_or_else(|| Error::Auth("missing uhs in XBL response".into()))
}

/// Build the Microsoft authorization URL the user visits in their browser.
pub fn authorize_url(config: &AuthConfig, state: &str) -> Result<String> {
    authorize_url_with_prompt(config, state, "select_account")
}

/// Same as [`authorize_url`] but with an explicit `prompt` value.
/// `select_account` is the everyday default (avoids signing in with the
/// wrong cached account); `consent` forces the permission screen again so a
/// missing `XboxLive.signin` grant can actually be repaired in the browser.
pub fn authorize_url_with_prompt(
    config: &AuthConfig,
    state: &str,
    prompt: &str,
) -> Result<String> {
    let (base, client_id, scopes) = match config.mode {
        AuthMode::AzureApp => {
            validate_client_id(&config.client_id)?;
            (MSA_AUTHORIZE_URL, config.client_id.as_str(), MSA_SCOPES)
        }
        // The title ID is a fixed first-party identifier, not an Azure GUID —
        // the GUID shape check does not apply to it.
        AuthMode::OfficialTitle => (
            LIVE_AUTHORIZE_URL,
            OFFICIAL_TITLE_CLIENT_ID,
            LIVE_SCOPES,
        ),
    };
    let mut url =
        reqwest::Url::parse(base).map_err(|e| Error::Other(e.to_string()))?;
    url.query_pairs_mut()
        .append_pair("client_id", client_id)
        .append_pair("response_type", "code")
        .append_pair("redirect_uri", &config.redirect_uri)
        .append_pair("response_mode", "query")
        .append_pair("scope", scopes)
        // Show the account picker by default: the #1 silent cause of Xbox
        // 400s is the browser being signed into a *different* Microsoft
        // account (no Minecraft, no Xbox profile) than the player's.
        // Interactive login is rare (refresh tokens handle the rest), so the
        // extra click is worth it. Pass "consent" to force the permission
        // screen when repairing a missing XboxLive.signin grant.
        .append_pair("prompt", prompt)
        .append_pair("state", state);
    Ok(url.to_string())
}

/// Random `state` for one login attempt (CSRF check on the loopback callback).
pub fn new_flow_state() -> String {
    // 128-bit CSPRNG state per RFC 6749 §10.12: the loopback callback
    // accepts a code only when it echoes this state, which is the entire
    // CSRF defense. (Was FNV-1a over time^pid: guessable, and identical for
    // calls within the same millisecond — caught by flow_states_look_random.)
    let mut bytes = [0u8; 16];
    rand::thread_rng().fill(&mut bytes);
    hex::encode(bytes)
}

/// Percent-decode a query component (`+` becomes space).
pub fn percent_decode(s: &str) -> String {
    let mut out = Vec::with_capacity(s.len());
    let bytes = s.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len() => {
                let hex = &s[i + 1..i + 3];
                if let Ok(b) = u8::from_str_radix(hex, 16) {
                    out.push(b);
                    i += 3;
                    continue;
                }
                out.push(b'%');
                i += 1;
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Parse the loopback redirect request target
/// (`/auth/callback?code=...&state=...`), verify `state`, return the code.
/// Microsoft errors (`?error=access_denied...`) become `Error::Auth`.
pub fn parse_auth_callback(target: &str, expected_state: &str) -> Result<String> {
    let query = target.split_once('?').map(|(_, q)| q).unwrap_or("");
    // Strip any fragment; split off extra path noise defensively.
    let query = query.split_once('#').map(|(q, _)| q).unwrap_or(query);
    let mut code: Option<String> = None;
    let mut state: Option<String> = None;
    let mut error: Option<String> = None;
    let mut error_description: Option<String> = None;
    for pair in query.split('&') {
        let (k, v) = pair.split_once('=').unwrap_or((pair, ""));
        match k {
            "code" => code = Some(percent_decode(v)),
            "state" => state = Some(percent_decode(v)),
            "error" => error = Some(percent_decode(v)),
            "error_description" => error_description = Some(percent_decode(v)),
            _ => {}
        }
    }
    if let Some(err) = error {
        let detail = error_description.unwrap_or_default();
        if err == "access_denied" {
            return Err(Error::Auth("Sign-in was cancelled in the browser.".into()));
        }
        return Err(Error::Auth(format!("Microsoft sign-in failed ({err}): {detail}")));
    }
    match (code, state) {
        (Some(c), Some(s)) if s == expected_state => Ok(c),
        (Some(_), Some(_)) => Err(Error::Auth("Login response did not match this attempt — try again.".into())),
        (Some(_), None) => Err(Error::Auth("Login response was missing its state check — try again.".into())),
        _ => Err(Error::Auth("No authorization code in the login response.".into())),
    }
}

/// Map an XBL failure to a human message. XBL rarely returns structured
/// errors, so the raw body is included (truncated) for diagnosis instead of
/// surfacing a bare `400 Bad Request`. Mode-aware: the plausible causes of a
/// 400 differ between the title flow and the Azure flow.
pub fn map_xbl_error(status: u16, body: &str, mode: AuthMode) -> String {
    let snippet: String = body.chars().take(300).collect();
    let detail = if snippet.trim().is_empty() {
        String::new()
    } else {
        format!(" Server said: {snippet}")
    };
    match (status, mode) {
        (400, AuthMode::OfficialTitle) => format!(
            "Xbox rejected the Microsoft sign-in (HTTP 400). In Official mode this is an \
             account-side rejection: Xbox Live needs a personal Microsoft account (not a \
             work/school account) that has signed in to xbox.com at least once, and the code \
             at microsoft.com/link must be entered with the account that owns \
             Minecraft.{detail}"
        ),
        (400, AuthMode::AzureApp) => format!(
            "Xbox rejected the Microsoft sign-in (HTTP 400). Usual causes: the Azure app behind \
             the Client ID has not been approved by Microsoft (submit it at https://aka.ms/mce-reviewappid, \
             or switch Settings → Microsoft sign-in → Sign-in method to Official, which needs no approval), \
             the Microsoft token has no XboxLive.signin grant (fix it with the in-app Re-consent sign-in), \
             or this is a work/school account — Xbox Live needs a personal Microsoft account.{detail}"
        ),
        (401 | 403, _) => format!(
            "Xbox rejected the Microsoft sign-in (HTTP {status}): the Microsoft token was not \
             accepted. Sign out of other Microsoft sessions in the browser, sign in with the \
             account that owns Minecraft, and retry.{detail}"
        ),
        _ => format!(
            "Xbox sign-in failed (HTTP {status}). Please retry; if it persists, check Xbox Live status.{detail}"
        ),
    }
}
/// Map an XSTS failure to a human message. XSTS reports errors as JSON with
/// an `XErr` code; known account problems get actionable text.
pub fn map_xsts_error(status: u16, body: &str) -> String {
    let xerr: Option<u64> = serde_json::from_str::<serde_json::Value>(body)
        .ok()
        .and_then(|v| {
            v.get("XErr").and_then(|x| {
                x.as_u64().or_else(|| x.as_str().and_then(|s| s.parse().ok()))
            })
        });
    match xerr {
        Some(2148916233) => "This Microsoft account has no Xbox profile yet — sign in once at xbox.com, then retry.".to_string(),
        Some(2148916235) => "Xbox blocked this sign-in (XErr 2148916235): the account's region or age settings forbid Xbox Live. Check account.xbox.com settings.".to_string(),
        Some(2148916238) => "This is a child account: an adult must approve Xbox multiplayer at family.microsoft.com, then retry.".to_string(),
        Some(code) => format!("Xbox authorization failed (XErr {code}, HTTP {status}). See https://minecraft.wiki/w/Microsoft_authentication"),
        None => format!("Xbox authorization failed (HTTP {status}). Please retry; if it persists, check Xbox Live status."),
    }
}

/// Map a Microsoft token-endpoint failure to a human message. Unlike the
/// Xbox endpoints, MSA returns structured `error` + `error_description`
/// (with `AADSTS…` codes), so a bare `error_for_status` would throw away the
/// actual cause — capture the body and translate the common cases.
pub fn map_msa_error(status: u16, body: &str) -> String {
    let parsed: serde_json::Value = serde_json::from_str(body).unwrap_or_default();
    let err = parsed.get("error").and_then(|e| e.as_str()).unwrap_or("");
    let desc = parsed
        .get("error_description")
        .and_then(|e| e.as_str())
        .unwrap_or("");
    let blob = format!("{err} {desc}");
    if blob.contains("AADSTS50011") {
        return "Microsoft refused the login (AADSTS50011: redirect URI mismatch): the Azure app's \
            Redirect URI must exactly match the launcher's loopback address \
            (http://127.0.0.1:19735, platform Mobile and desktop applications), including \
            http vs https, host, port and trailing slash. Fix the registration, then retry."
            .to_string();
    }
    if blob.contains("AADSTS700016") || blob.contains("AADSTS7000218") || blob.contains("AADSTS90002") {
        return format!(
            "Microsoft does not recognise this Azure app (HTTP {status}): the Auth Client ID in \
             Settings → Microsoft sign-in is wrong or belongs to a deleted registration. Verify it \
             in the Azure portal or clear it to use the shipped default, then retry."
        );
    }
    if blob.contains("AADSTS65001") || blob.contains("AADSTS65002") || blob.to_lowercase().contains("consent") {
        return "Microsoft requires permission approval (consent) for XboxLive.signin: run the \
            in-app Re-consent sign-in and approve the permissions in the browser, then retry."
            .to_string();
    }
    if blob.contains("AADSTS50020") || blob.contains("AADSTS90009") {
        return "This looks like a work/school account, which Xbox Live rejects: sign in with a \
            personal Microsoft account that owns Minecraft: Java Edition."
            .to_string();
    }
    if err == "invalid_grant" {
        return "The browser sign-in expired before the launcher could use it (invalid_grant): \
            approve the Microsoft page promptly and retry — or just sign in again."
            .to_string();
    }
    let snippet: String = if desc.trim().is_empty() {
        body.chars().take(300).collect()
    } else {
        desc.chars().take(300).collect()
    };
    let detail = if snippet.trim().is_empty() {
        String::new()
    } else {
        format!(" Server said: {snippet}")
    };
    format!("Microsoft sign-in failed (HTTP {status}{}).{detail}", if err.is_empty() { String::new() } else { format!(" {err}") })
}

/// Exchange a Microsoft access token for a full Minecraft auth result.
pub async fn authenticate(
    client: &reqwest::Client,
    ms_access_token: &str,
    mode: AuthMode,
) -> Result<McAuth> {
    let (uhs, xsts_token) = match mode {
        // Title clients go through SISU: user/authenticate no longer accepts
        // their tickets (HTTP 400 for both prefixes, verified live).
        AuthMode::OfficialTitle => authenticate_title_sisu(client, ms_access_token).await?,
        AuthMode::AzureApp => {
            // XBL (body-aware errors: a bare 400 hides the real cause, so the
            // response body is captured and mapped like XSTS below). Azure v2
            // tokens are `d=`-prefixed RPS tickets.
            let xbl_resp = client
                .post(XBL_AUTH_URL)
                .header(reqwest::header::ACCEPT, "application/json")
                .header("X-Xbl-Contract-Version", "1")
                .json(&serde_json::json!({
                    "AuthMethod": "RPS",
                    "SiteName": "user.auth.xboxlive.com",
                    "RpsTicket": format!("d={ms_access_token}"),
                    "RelyingParty": "http://auth.xboxlive.com",
                    "TokenType": "JWT"
                }))
                .send()
                .await?;
            if !xbl_resp.status().is_success() {
                let status = xbl_resp.status().as_u16();
                let body = xbl_resp.text().await.unwrap_or_default();
                return Err(Error::Auth(map_xbl_error(status, &body, mode)));
            }
            let xbl: XblResponse = xbl_resp.json().await?;
            let uhs = xbl_uhs(&xbl)?;

            // XSTS (body-aware errors: XErr codes carry the real reason)
            let xsts_resp = client
                .post(XSTS_AUTH_URL)
                .header(reqwest::header::ACCEPT, "application/json")
                .header("X-Xbl-Contract-Version", "1")
                .json(&serde_json::json!({
                    "SandboxId": "RETAIL",
                    "UserTokens": [xbl.Token],
                    "RelyingParty": "rp://api.minecraftservices.com/",
                    "TokenType": "JWT"
                }))
                .send()
                .await?;
            if !xsts_resp.status().is_success() {
                let status = xsts_resp.status().as_u16();
                let body = xsts_resp.text().await.unwrap_or_default();
                return Err(Error::Auth(map_xsts_error(status, &body)));
            }
            let xsts: XblResponse = xsts_resp.json().await?;
            (uhs, xsts.Token)
        }
    };

    // Minecraft services. A 403 here almost always means the Azure app
    // was never approved for the Minecraft-services API (review form).
    let mcs_resp = client
        .post(MCS_LOGIN_URL)
        .json(&serde_json::json!({
            "identityToken": format!("XBL3.0 x={uhs};{xsts_token}")
        }))
        .send()
        .await?;
    if mcs_resp.status() == reqwest::StatusCode::FORBIDDEN {
        return Err(Error::Auth(
            "Minecraft services refused the login (403): the Azure app registration needs Minecraft-services API approval (Microsoft review form) before real logins work.".into(),
        ));
    }
    let mcs: McsLoginResponse = mcs_resp.error_for_status()?.json().await?;

    Ok(McAuth {
        access_token: mcs.access_token,
        xuid: uhs,
        expires_in: mcs.expires_in,
    })
}

/// Fetch the player profile with an MC access token.
pub async fn fetch_profile(client: &reqwest::Client, mc_access_token: &str) -> Result<ProfileResponse> {
    let profile: ProfileResponse = client
        .get(MCS_PROFILE_URL)
        .bearer_auth(mc_access_token)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    Ok(profile)
}

/// Check game ownership with an MC access token.
pub async fn has_entitlements(client: &reqwest::Client, mc_access_token: &str) -> Result<bool> {
    #[derive(Deserialize)]
    struct Entitlements {
        #[serde(rename = "items", default)]
        items: Vec<serde_json::Value>,
    }
    let ent: Entitlements = client
        .get(MCS_ENTITLEMENTS_URL)
        .bearer_auth(mc_access_token)
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    Ok(!ent.items.is_empty())
}

/// Exchange an authorization code for MSA tokens (auth-code flow).
pub async fn exchange_code(
    client: &reqwest::Client,
    config: &AuthConfig,
    code: &str,
) -> Result<(String, String)> {
    if config.mode == AuthMode::AzureApp {
        validate_client_id(&config.client_id)?;
    }
    let raw = client
        .post(config.mode.token_url())
        .form(&[
            ("client_id", config.client_id.as_str()),
            // Repeat the authorize scopes (RFC 6749 §4.1.3): without this,
            // some Entra setups return a token without the XboxLive.signin
            // grant, which then fails three hops later as an opaque Xbox 400.
            ("scope", config.mode.scopes()),
            ("code", code),
            ("grant_type", "authorization_code"),
            ("redirect_uri", config.redirect_uri.as_str()),
        ])
        .send()
        .await?;
    if !raw.status().is_success() {
        let status = raw.status().as_u16();
        let body = raw.text().await.unwrap_or_default();
        return Err(Error::Auth(map_msa_error(status, &body)));
    }
    let body = raw.text().await.unwrap_or_default();
    let resp: MsaTokenResponse = serde_json::from_str(&body)
        .map_err(|e| Error::Auth(format!("Microsoft returned an unreadable token response: {e}")))?;
    let refresh = resp.refresh_token.ok_or_else(|| {
        Error::Auth(
            "Microsoft did not return a refresh token (the offline_access permission was not \
             consented) — run the Re-consent sign-in and approve the permissions, then retry."
                .into(),
        )
    })?;
    Ok((resp.access_token, refresh))
}

/// Refresh MSA tokens silently.
pub async fn refresh(client: &reqwest::Client, config: &AuthConfig, refresh_token: &str) -> Result<(String, String)> {
    if refresh_token.trim().is_empty() {
        return Err(Error::Auth(
            "This session cannot be renewed silently (no refresh token was issued) — sign in again.".into(),
        ));
    }
    if config.mode == AuthMode::AzureApp {
        validate_client_id(&config.client_id)?;
    }
    let raw = client
        .post(config.mode.token_url())
        .form(&[
            ("client_id", config.client_id.as_str()),
            // Keep the XboxLive.signin grant across silent refreshes (RFC 6749
            // §6 allows re-requesting the same scope); a refresh that drops
            // the grant would otherwise surface later as an Xbox 400.
            ("scope", config.mode.scopes()),
            ("refresh_token", refresh_token),
            ("grant_type", "refresh_token"),
        ])
        .send()
        .await?;
    if !raw.status().is_success() {
        let status = raw.status().as_u16();
        let body = raw.text().await.unwrap_or_default();
        return Err(Error::Auth(map_msa_error(status, &body)));
    }
    let body = raw.text().await.unwrap_or_default();
    let resp: MsaTokenResponse = serde_json::from_str(&body)
        .map_err(|e| Error::Auth(format!("Microsoft returned an unreadable token response: {e}")))?;
    let refresh = resp.refresh_token.ok_or_else(|| {
        Error::Auth(
            "Microsoft did not return a refresh token (the offline_access permission was not \
             consented) — sign in again and approve the permissions.".into(),
        )
    })?;
    Ok((resp.access_token, refresh))
}

/// Full login from a Microsoft access token to a `Session`.
pub async fn full_login(
    client: &reqwest::Client,
    refresh_token: String,
    ms_access_token: &str,
    mode: AuthMode,
) -> Result<Session> {
    let auth = authenticate(client, ms_access_token, mode).await?;
    let profile = fetch_profile(client, &auth.access_token).await?;
    let (skin_url, skin_variant) = match profile.active_skin() {
        Some(s) => (s.url.clone(), s.variant.clone()),
        None => (String::new(), String::new()),
    };
    Ok(Session {
        access_token: auth.access_token,
        expires_at: now_millis() + auth.expires_in.saturating_sub(60) * 1000,
        uuid: profile.id,
        username: profile.name,
        xuid: auth.xuid,
        refresh_token,
        skin_url,
        skin_variant,
    })
}

/// Return the session unchanged when fresh, otherwise silently re-login with
/// the stored refresh token.
pub async fn refresh_session(
    client: &reqwest::Client,
    config: &AuthConfig,
    session: &Session,
) -> Result<Session> {
    if !session.is_expired(now_millis()) {
        return Ok(session.clone());
    }
    let (ms_token, refresh) = refresh(client, config, &session.refresh_token).await?;
    full_login(client, refresh, &ms_token, config.mode).await
}

/// Upload a skin PNG to the signed-in Mojang account.
/// `variant` is `"classic"` or `"slim"`. The API is
/// `POST /minecraft/profile/skins` with multipart `file` + `variant` (the
/// same request Prism Launcher sends); PUT is the retired 2013-era
/// sessionserver API and gets a 405 here.
pub async fn upload_skin(
    client: &reqwest::Client,
    session: &Session,
    png: Vec<u8>,
    variant: &str,
) -> Result<()> {
    let file_part = reqwest::multipart::Part::bytes(png)
        .file_name("skin.png")
        .mime_str("image/png")
        .map_err(|e| Error::Other(e.to_string()))?;
    let form = reqwest::multipart::Form::new()
        .text("variant", variant.to_string())
        .part("file", file_part);
    let resp = client
        .post(MCS_SKINS_URL)
        .bearer_auth(&session.access_token)
        .multipart(form)
        .send()
        .await?;
    if !resp.status().is_success() {
        let status = resp.status().as_u16();
        let body = resp.text().await.unwrap_or_default();
        return Err(Error::Auth(format!(
            "skin upload failed (HTTP {status}): {}",
            truncate(&body, 300)
        )));
    }
    Ok(())
}

/// Reset the account to a default skin (DELETE the active skin).
pub async fn reset_skin(client: &reqwest::Client, session: &Session) -> Result<()> {
    let resp = client
        .delete(format!("{MCS_SKINS_URL}/active"))
        .bearer_auth(&session.access_token)
        .send()
        .await?;
    if !resp.status().is_success() {
        let status = resp.status().as_u16();
        let body = resp.text().await.unwrap_or_default();
        return Err(Error::Auth(format!(
            "skin reset failed (HTTP {status}): {}",
            truncate(&body, 300)
        )));
    }
    Ok(())
}

/// Download the PNG behind a skin URL (textures.minecraft.net, a few KB) —
/// used to render the account avatar without a CORS round-trip in the
/// webview.
pub async fn fetch_skin_png(client: &reqwest::Client, skin_url: &str) -> Result<Vec<u8>> {
    if skin_url.trim().is_empty() {
        return Err(Error::Auth("account has no skin".into()));
    }
    let bytes = client
        .get(skin_url)
        .send()
        .await?
        .error_for_status()?
        .bytes()
        .await?
        .to_vec();
    Ok(bytes)
}

fn truncate(s: &str, max: usize) -> String {
    s.chars().take(max).collect()
}

pub fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config() -> AuthConfig {
        AuthConfig {
            client_id: "0e36efd3-4bb6-4bee-ac76-d33ac47fd3df".into(),
            redirect_uri: "http://127.0.0.1:1234/auth/callback".into(),
            mode: AuthMode::AzureApp,
        }
    }

    fn title_config() -> AuthConfig {
        AuthConfig {
            client_id: OFFICIAL_TITLE_CLIENT_ID.into(),
            redirect_uri: "http://127.0.0.1:1234/auth/callback".into(),
            mode: AuthMode::OfficialTitle,
        }
    }

    /// Microsoft's device-code response includes `verification_uri_complete`
    /// (the page with the code prefilled via ?otc=). Opening that instead of
    /// the bare verification page removes the code-typing step.
    #[test]
    fn device_code_start_parses_prefilled_uri() {
        let json = r#"{
            "device_code": "dc", "user_code": "ABCD1234",
            "verification_uri": "https://login.live.com/device",
            "verification_uri_complete": "https://login.live.com/device?otc=ABCD1234",
            "interval": 5, "expires_in": 900
        }"#;
        let s: DeviceCodeStart = serde_json::from_str(json).unwrap();
        assert_eq!(
            s.verification_uri_complete.as_deref(),
            Some("https://login.live.com/device?otc=ABCD1234")
        );
    }

    /// Some tenants omit it — the flow must fall back to the bare page.
    #[test]
    fn device_code_start_tolerates_missing_prefilled_uri() {
        let json = r#"{
            "device_code": "dc", "user_code": "AB",
            "verification_uri": "https://login.live.com/device"
        }"#;
        let s: DeviceCodeStart = serde_json::from_str(json).unwrap();
        assert!(s.verification_uri_complete.is_none());
    }

    #[test]
    fn authorize_url_carries_all_params() {
        let url = authorize_url(&config(), "state123").unwrap();
        assert!(url.starts_with(MSA_AUTHORIZE_URL));
        assert!(url.contains("client_id=0e36efd3-4bb6-4bee-ac76-d33ac47fd3df"));
        assert!(url.contains("response_type=code"));
        assert!(url.contains("response_mode=query"));
        assert!(url.contains("scope=XboxLive.signin+offline_access") || url.contains("scope=XboxLive.signin%20offline_access"));        assert!(url.contains("state=state123"));
        assert!(url.contains("redirect_uri="));
        assert!(url.contains("prompt=select_account"));
    }

    #[test]
    fn reconsent_url_forces_consent_prompt() {
        let url = authorize_url_with_prompt(&config(), "s", "consent").unwrap();
        assert!(url.contains("prompt=consent"));
        assert!(!url.contains("prompt=select_account"));
    }

    #[test]
    fn authorize_url_rejects_bad_client_id() {
        let bad = AuthConfig {
            client_id: "not-a-guid".into(),
            redirect_uri: "http://127.0.0.1:1234".into(),
            mode: AuthMode::AzureApp,
        };
        assert!(authorize_url(&bad, "s").is_err());
        let empty = AuthConfig {
            client_id: "   ".into(),
            redirect_uri: "http://127.0.0.1:1234".into(),
            mode: AuthMode::AzureApp,
        };
        assert!(authorize_url(&empty, "s").is_err());
    }

    #[test]
    fn official_mode_uses_live_endpoints_and_title_id() {
        let url = authorize_url(&title_config(), "state123").unwrap();
        assert!(url.starts_with(LIVE_AUTHORIZE_URL));
        assert!(url.contains(&format!("client_id={OFFICIAL_TITLE_CLIENT_ID}")));
        // Title-auth scope: the MBI_SSL RPS ticket that pairs with the `t=`
        // prefix at user.auth.xboxlive.com, not the v2 signin scope.
        assert!(url.contains("MBI_SSL"));
        assert!(url.contains("user.auth.xboxlive.com"));
        assert!(!url.contains("XboxLive.signin"));
        assert!(!url.contains("offline_access"));
        assert!(url.contains("state=state123"));
        assert!(url.contains("prompt=select_account"));
    }

    #[test]
    fn auth_mode_routing_is_consistent() {
        assert_eq!(AuthMode::from_setting("official"), AuthMode::OfficialTitle);
        assert_eq!(AuthMode::from_setting(" Official "), AuthMode::OfficialTitle);
        assert_eq!(AuthMode::from_setting("azure"), AuthMode::AzureApp);
        assert_eq!(AuthMode::from_setting("garbage"), AuthMode::AzureApp);
        // The scope and the RpsTicket prefix must stay paired: MBI_SSL
        // yields the `t=` title ticket, XboxLive.signin the `d=` ticket.
        assert!(AuthMode::OfficialTitle.scopes().contains("MBI_SSL"));
        assert_eq!(AuthMode::AzureApp.scopes(), MSA_SCOPES);
        assert_eq!(AuthMode::AzureApp.token_url(), MSA_TOKEN_URL);
        assert_eq!(AuthMode::OfficialTitle.token_url(), LIVE_TOKEN_URL);
    }

    #[test]
    fn device_poll_pending_and_slowdown_keep_polling() {
        assert!(device_poll_outcome(400, r#"{"error":"authorization_pending"}"#).unwrap().is_none());
        assert!(device_poll_outcome(400, r#"{"error":"slow_down"}"#).unwrap().is_none());
    }

    #[test]
    fn device_poll_success_returns_tokens() {
        let (access, refresh) = device_poll_outcome(
            200,
            r#"{"access_token":"at","refresh_token":"rt","expires_in":86400}"#,
        )
        .unwrap()
        .unwrap();
        assert_eq!((access.as_str(), refresh.as_str()), ("at", "rt"));
    }

    #[test]
    fn device_poll_maps_real_errors() {
        assert!(device_poll_outcome(400, r#"{"error":"expired_token"}"#).is_err());
        // A missing refresh token degrades to a non-refreshable session
        // instead of failing a sign-in that just succeeded.
        let (access, refresh) =
            device_poll_outcome(200, r#"{"access_token":"at"}"#).unwrap().unwrap();
        assert_eq!((access.as_str(), refresh.as_str()), ("at", ""));
    }

    #[test]
    fn client_id_validation_accepts_guids() {
        assert!(validate_client_id("0e36efd3-4bb6-4bee-ac76-d33ac47fd3df").is_ok());
        assert!(validate_client_id("  0E36EFD3-4BB6-4BEE-AC76-D33AC47FD3DF  ").is_ok());
        assert!(validate_client_id("").is_err());
        assert!(validate_client_id("test-client").is_err());
        assert!(validate_client_id("0e36efd3-4bb6-XXXX-ac76-d33ac47fd3df").is_err());
    }

    #[test]
    fn msa_errors_map_to_actionable_guidance() {
        assert!(map_msa_error(400, r#"{"error":"invalid_grant","error_description":"AADSTS50011: redirect mismatch"}"#).contains("AADSTS50011"));
        assert!(map_msa_error(400, r#"{"error":"unauthorized_client","error_description":"AADSTS700016: app not found"}"#).contains("Auth Client ID"));
        assert!(map_msa_error(400, r#"{"error":"invalid_grant","error_description":"AADSTS65001: consent required"}"#).contains("Re-consent"));
        assert!(map_msa_error(400, r#"{"error":"invalid_grant","error_description":"AADSTS50020: work account"}"#).contains("work/school"));
        assert!(map_msa_error(400, r#"{"error":"invalid_grant","error_description":"code expired"}"#).contains("invalid_grant"));
        assert!(map_msa_error(500, "").contains("500"));
    }

    #[test]
    fn callback_parses_code_and_state() {
        let code = parse_auth_callback("/auth/callback?code=ABC123&state=s1", "s1").unwrap();
        assert_eq!(code, "ABC123");
    }

    #[test]
    fn callback_rejects_state_mismatch() {
        assert!(parse_auth_callback("/auth/callback?code=A&state=evil", "s1").is_err());
    }

    #[test]
    fn callback_maps_access_denied() {
        let err = parse_auth_callback("/auth/callback?error=access_denied&error_description=user+cancelled", "s1")
            .unwrap_err();
        assert!(err.to_string().contains("cancelled"));
    }

    #[test]
    fn callback_decodes_percent_encoding() {
        let code = parse_auth_callback("/auth/callback?code=A%2BB%20C&state=s", "s").unwrap();
        assert_eq!(code, "A+B C");
    }

    #[test]
    fn xbl_errors_carry_guidance_and_body() {
        assert!(map_xbl_error(400, "", AuthMode::AzureApp).contains("XboxLive.signin"));
        // Official-mode 400s must not point users at the Azure review form.
        let official = map_xbl_error(400, "", AuthMode::OfficialTitle);
        assert!(official.contains("xbox.com"));
        assert!(!official.contains("aka.ms/mce-reviewappid"));
        assert!(map_xbl_error(400, "some server text", AuthMode::AzureApp).contains("some server text"));
        assert!(map_xbl_error(403, "", AuthMode::AzureApp).contains("owns Minecraft"));
        assert!(map_xbl_error(500, "", AuthMode::OfficialTitle).contains("500"));
    }

    #[test]
    fn xsts_error_codes_map_to_guidance() {
        assert!(map_xsts_error(401, r#"{"XErr":2148916233}"#).contains("xbox.com"));
        assert!(map_xsts_error(401, r#"{"XErr":2148916238}"#).contains("family.microsoft.com"));
        assert!(map_xsts_error(401, "{}").contains("401"));
    }

    #[test]
    fn session_expiry_respects_skew() {
        let fresh = Session {
            access_token: String::new(),
            expires_at: now_millis() + 3_600_000,
            uuid: String::new(),
            username: String::new(),
            xuid: String::new(),
            refresh_token: String::new(),
            skin_url: String::new(),
            skin_variant: String::new(),
        };
        assert!(!fresh.is_expired(now_millis()));
        let stale = Session { expires_at: now_millis() + 30_000, ..fresh.clone() };
        assert!(stale.is_expired(now_millis()));
    }

    #[test]
    fn old_sessions_without_xuid_still_load() {
        let json = r#"{"access_token":"a","expires_at":1,"uuid":"u","username":"n","refresh_token":"r"}"#;
        let s: Session = serde_json::from_str(json).unwrap();
        assert_eq!(s.xuid, "");
    }

    #[test]
    fn flow_states_look_random() {
        assert_ne!(new_flow_state(), new_flow_state());
    }

    #[test]
    fn windows_filetime_matches_epoch_offset() {
        assert_eq!(windows_filetime(0), 116_444_736_000_000_000);
    }

    #[test]
    fn xbox_signature_payload_layout_matches_minecraftauth() {
        let got = xbox_signature_payload(1, "POST", "/authorize", None, b"x");
        let mut want = Vec::new();
        want.extend_from_slice(&1u32.to_be_bytes());
        want.push(0);
        want.extend_from_slice(&1u64.to_be_bytes());
        want.push(0);
        want.extend_from_slice(b"POST");
        want.push(0);
        want.extend_from_slice(b"/authorize");
        want.push(0);
        // No Authorization value — the trailing separator is still present.
        want.push(0);
        want.extend_from_slice(b"x");
        want.push(0);
        assert_eq!(got, want);

        let with_auth = xbox_signature_payload(2, "POST", "/a", Some("Bearer t"), b"");
        let mut want2 = Vec::new();
        want2.extend_from_slice(&1u32.to_be_bytes());
        want2.push(0);
        want2.extend_from_slice(&2u64.to_be_bytes());
        want2.push(0);
        want2.extend_from_slice(b"POST");
        want2.push(0);
        want2.extend_from_slice(b"/a");
        want2.push(0);
        want2.extend_from_slice(b"Bearer t");
        want2.push(0);
        want2.push(0);
        assert_eq!(with_auth, want2);
    }

    #[test]
    fn xbox_signature_header_carries_policy_timestamp_and_signature() {
        use base64::Engine as _;
        let mut sig = [0u8; 64];
        sig[0] = 0xAB;
        sig[63] = 0xCD;
        let decoded = base64::engine::general_purpose::STANDARD
            .decode(xbox_signature_header(7, &sig))
            .unwrap();
        assert_eq!(decoded.len(), 12 + 64);
        assert_eq!(&decoded[0..4], &1u32.to_be_bytes());
        assert_eq!(&decoded[4..12], &7u64.to_be_bytes());
        assert_eq!(&decoded[12..], &sig[..]);
    }

    #[test]
    fn xbox_proof_key_is_p256_jwk() {
        let secret = p256::SecretKey::random(&mut p256::elliptic_curve::rand_core::OsRng);
        let signing_key = p256::ecdsa::SigningKey::from(&secret);
        let jwk = xbox_proof_key(&p256::ecdsa::VerifyingKey::from(&signing_key));
        assert_eq!(jwk["kty"], "EC");
        assert_eq!(jwk["crv"], "P-256");
        assert_eq!(jwk["alg"], "ES256");
        assert_eq!(jwk["use"], "sig");
        // 32-byte coordinates encode to 43 unpadded base64url chars.
        for k in ["x", "y"] {
            let s = jwk[k].as_str().unwrap();
            assert_eq!(s.len(), 43);
            assert!(!s.contains('=') && !s.contains('+') && !s.contains('/'));
        }
    }

    #[test]
    fn random_device_id_is_braced_uuid_v4() {
        let id = random_device_id();
        assert!(id.starts_with('{') && id.ends_with('}'));
        let inner = &id[1..37];
        assert_eq!(inner.len(), 36);
        assert_eq!(inner.chars().filter(|c| *c == '-').count(), 4);
        assert_eq!(inner.as_bytes()[14], b'4'); // version nibble
        assert!("89ab".contains(inner.as_bytes()[19] as char)); // variant nibble
        assert_ne!(random_device_id(), random_device_id());
    }

    /// Live probe of the Xbox proof-of-possession signing (device
    /// registration is anonymous — no Microsoft account involved). If the
    /// signature layout drifts from what Xbox expects, this fails with a
    /// 401 before any real sign-in does. Run explicitly:
    /// `cargo test -p fasterlauncher-core sisu_device -- --ignored --nocapture`
    #[test]
    #[ignore = "live network call"]
    fn sisu_device_registration_works_live() {
        tokio::runtime::Runtime::new().expect("tokio runtime").block_on(async {
            let client = reqwest::Client::new();
            let secret = p256::SecretKey::random(&mut p256::elliptic_curve::rand_core::OsRng);
            let signing_key = p256::ecdsa::SigningKey::from(&secret);
            let verifying_key = p256::ecdsa::VerifyingKey::from(&signing_key);
            let token = xbl_device_token(&client, &signing_key, &verifying_key)
                .await
                .expect("device registration should succeed");
            assert!(token.len() > 100, "implausible device token: {token}");
        });
    }
}
