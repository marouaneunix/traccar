# Taxi dashboard

The web app has a `/dashboard` page, reachable from the main navigation and Reports menu. It uses existing Traccar summary and trip reports for distance, driving time, active days, and trip count. Current status and the last GPS position are independent of the selected reporting dates. The map button opens the existing vehicle map.

## Connect Wallet

The integration reads the official BudgetBakers Wallet REST API. It never changes Wallet records. Financial endpoints require an authenticated Traccar administrator; vehicle report restrictions still apply. The token is read from the administrator-managed wallet_token computed attribute; it is not copied into device or position data.

Open **Settings → Computed Attributes**, press **+**, and add:

| Field | Value |
| --- | --- |
| Description | BudgetBakers API token |
| Attribute | `wallet_token` |
| Expression | `'YOUR_API_TOKEN'` (include the quotes) |
| Type | String |

Save a single definition. This uses the existing `tc_attributes` table and the existing computed-attribute create/edit/delete workflow. No new table, database migration, or separate token form is required. Token expressions must be constant strings; both single and double quotes are accepted. The backend reads the token without executing its expression. The definition applies globally to the dashboard and needs no device link.

Only administrators can read or manage `wallet_token`. It is excluded from position processing even if linked to a device. Administrators can see its expression in the normal computed-attribute editor. Editing or deleting it takes effect on the next dashboard request without a restart, and changing the active token invalidates cached finances. An optional `wallet.token` / `WALLET_TOKEN` server configuration value is used only when no `wallet_token` definition exists.

In **Settings → Devices → your taxi → Attributes**, add a **String** attribute with key `accountId` and value `af16c94a-e3a3-40b8-9d87-7faee7191ab9` (the Taxi account supplied for this project). The predefined **Wallet: Account ID** attribute uses that same key. Save the device. The dashboard uses the selected device's own Traccar ID and reads its current `accountId` attribute from storage; do not add a separate `deviceId` attribute. Each vehicle can have a different Wallet account. Missing or blank account attributes leave finances unavailable.

Changes to device `accountId` attributes take effect on the next dashboard load or refresh without a backend restart. Without a connection, the dashboard still displays vehicle activity and a setup message; unavailable finances are shown as unavailable, not zero.

Responses are scoped to the selected device's current account attribute, paginated completely, deduplicated, and cached separately per account and date range for 30–3600 seconds (300 by default). Date ranges are limited to 366 days and 20,000 records. Upstream failures do not produce partial totals. HTTP 429 pauses subsequent requests according to `Retry-After`. The dashboard shows fetch time and Wallet synchronization status.

## Classify financial records

Open **Classification** to map comma-separated Wallet label or category names. Matching is exact and case-insensitive. Defaults are `Received income`, `Expected earnings`, `Lost earnings`, `Operating cost`, `Advance`, `Household`, and `Adjustment`. A positive record in Wallet's Income category defaults to received driver income. Other unmatched records and conflicting rules require review. Descriptions are never automatically interpreted.

The transaction table also supports explicit per-record choices. These and the rules are saved under your own Traccar user's `walletDashboard` attribute, separately for each Wallet account. They do not modify BudgetBakers. Each administrator has their own classifications.

- **Received income** is an amount already received from a driver; missed-work deductions are not subtracted again.
- **Expected earnings** represents the daily amount expected before missed-work deductions.
- **Lost earnings** cancels expected income on the same recorded day, up to that day's expected amount. Unmatched deductions trigger a partial-results warning.
- **Operating costs** are actual taxi costs, such as maintenance, washing, and fuel.
- **Advances**, **household withdrawals**, and **settlement adjustments** remain separate from operations.

Adjusted earnings = received income + expected income − matched lost earnings. Operating result = adjusted earnings − operating costs. Your non-working-day example (440 expected, 440 lost, 50 maintenance) produces 0 adjusted earnings and a −50 MAD operating result.

Only cleared MAD records enter financial totals. Transfers are excluded. Other currencies, invalid or uncleared records, and unclassified entries are flagged. Recorded net movement includes all cleared MAD records except excluded/transfer records; it is not an account balance. Financial charts use `recordDate` in the browser's local timezone. Grouped payments stay on their recorded date; coverage periods in notes are not automatically allocated.

## Build and verify

```bash
./gradlew build
npm --prefix traccar-web run test:dashboard
npm --prefix traccar-web run lint
npm --prefix traccar-web run build
```

Point `web.path` to `./traccar-web/build` to serve the frontend from the Java process. See the [Wallet API reference](https://rest.budgetbakers.com/wallet/reference) for authentication, filtering, and synchronization behavior.
