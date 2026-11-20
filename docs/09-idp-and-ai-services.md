# 9 — IdentityProviderService and AIService

Two small services grouped here for compactness.

## 9.1 IdentityProviderService

`IdentityProviderService` manages the external identity providers (SSO) users can sign in with. OAuth2 is the only supported protocol.

| RPC | HTTP | Auth | Purpose |
|---|---|---|---|
| `ListIdentityProviders` | `GET /api/identity-providers` | Anonymous | List configured IDPs (for the sign-in page). `client_secret` never returned. |
| `GetIdentityProvider` | `GET /api/identity-providers/{idp}` | Anonymous | Get one IDP. `client_secret` never returned. |
| `CreateIdentityProvider` | `POST /api/identity-providers` | Admin | Create an IDP. |
| `UpdateIdentityProvider` | `PATCH /api/identity-providers/{idp}` | Admin | Update an IDP. Empty OAuth2 client secret keeps the stored one. |
| `DeleteIdentityProvider` | `DELETE /api/identity-providers/{idp}` | Admin | Delete an IDP. Fails if password sign-in is disabled and no other effective provider remains. |

### 9.1.1 `IdentityProvider` message

```json
{
  "name": "identity-providers/google",
  "type": "OAUTH2",               // OAUTH2 (only)
  "title": "Google",
  "identifierFilter": ".*@example\\.com",   // optional regex; empty = allow all
  "config": {
    "oauth2Config": {
      "clientId": "...apps.googleusercontent.com",
      "clientSecret": "",      // write-only; never returned
      "authUrl": "https://accounts.google.com/o/oauth2/v2/auth",
      "tokenUrl": "https://oauth2.googleapis.com/token",
      "userInfoUrl": "https://www.googleapis.com/oauth2/v3/userinfo",
      "scopes": ["openid", "email", "profile"],
      "fieldMapping": {
        "identifier": "sub",
        "displayName": "name",
        "email": "email",
        "avatarUrl": "picture"
      }
    }
  }
}
```

`identifierFilter` is a regex the user identifier must match; empty allows every identifier. `fieldMapping` maps OIDC claims to user fields. `identifier` is required (sign-in fails without it).

### 9.1.2 Use on Android

For the sign-in screen, `GET /api/identity-providers` returns the configured providers. Render each as a button labeled with the provider `title`. When the user taps, start an OAuth2 flow:

1. Build the authorization URL:
   ```
   <authUrl>?client_id=<clientId>&redirect_uri=<your-app-callback>&response_type=code&scope=<scopes joined by space>&state=<random>&code_challenge=<sha256-of-code-verifier>&code_challenge_method=S256
   ```
2. Open it in a Chrome Custom Tab.
3. Receive the redirect at a deep link / custom scheme you registered (`<your-app-callback>`).
4. Exchange the code via `POST /api/auth/signin` with `ssoCredentials`:
   ```json
   {
     "ssoCredentials": {
       "identityProvider": "identity-providers/google",
       "code": "<auth-code>",
       "redirectUri": "<your-app-callback>",
       "codeVerifier": "<PKCE-verifier>"
     }
   }
   ```

You almost certainly don't need this on Android — the PAT flow sidesteps SSO entirely. SSO is most useful for browser sign-in.

### 9.1.3 Notes

- The `clientSecret` is required on create/update but never returned. An empty value on update keeps the stored secret (so you can change `title` without re-entering it).
- Providers configured by declarative configuration files cannot be updated or deleted (returns `FAILED_PRECONDITION` with `MANAGED_BY_CONFIGURATION`).
- Deleting the last effective provider when password sign-in is disabled for regular users returns `FAILED_PRECONDITION`.

---

## 9.2 AIService

`AIService` runs AI tasks through the AI providers configured on the instance.

| RPC | HTTP | Auth | Purpose |
|---|---|---|---|
| `Transcribe` | `POST /api/ai:transcribe` | Required | Transcribe an audio file with the configured transcription provider. |

### 9.2.1 `TranscribeRequest`

```json
{
  "audio": {
    "content": "<base64-bytes>",   // input only; at most 25 MiB
    "filename": "voice.mp4",        // optional; at most 255 chars
    "contentType": "audio/mp4"      // optional; auto-detected if empty
  }
}
```

`uri` is reserved for future use; setting it is rejected. Only inline `content` works.

### 9.2.2 `TranscribeResponse`

```json
{ "text": "Hello, this is a test." }
```

### 9.2.3 Errors

| Code | HTTP | Reason |
|---|---|---|
| `UNAUTHENTICATED` | 401 | No signed-in user. |
| `PERMISSION_DENIED` | 403 | Transcription is disabled (no `providerId` set in `AISetting.transcription`). |
| `INVALID_ARGUMENT` | 400 | `content` larger than 25 MiB, unsupported audio format, missing `content` and `uri`. |
| `RESOURCE_EXHAUSTED` | 429 | Rate limited per user (the proto comment says "rate limited per user"). |
| `INTERNAL` | 500 | Provider error (OpenAI / Gemini returned an error). |

### 9.2.4 Android integration

Use this to power an audio recorder in the memo editor: record a voice memo with `MediaRecorder`, base64-encode the bytes, POST to `/api/ai:transcribe`, and insert the returned text into the editor.

```kotlin
suspend fun transcribe(client: OkHttpClient, baseUrl: String, pat: String, audioFile: File): String {
    val bytes = audioFile.readBytes()
    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
    val body = buildJsonObject {
        putJsonObject("audio") {
            put("content", b64)
            put("filename", audioFile.name)
            put("contentType", "audio/mp4")
        }
    }.toString()
    val req = Request.Builder()
        .url("$baseUrl/api/ai:transcribe")
        .header("Authorization", "Bearer $pat")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()
    client.newCall(req).execute().use { resp ->
        val json = resp.body!!.string().parseJson<TranscribeResponse>()
        return json.text
    }
}
```

Check `AISetting.transcription.providerId` before showing the transcribe button — if it's empty, transcription is disabled and the call will return `PERMISSION_DENIED`.

---

Next: [10 — Filter (CEL) syntax](10-filter-cel-syntax.md)
