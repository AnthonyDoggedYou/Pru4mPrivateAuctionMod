# Donut Auction (client-side Fabric mod for DonutSMP, Minecraft 26.3)

A player-hosted auction tool. You list an item, other players bid **in chat**, the highest valid bid
wins when the timer hits zero, and the mod tracks who won, who paid, and whether you delivered.

> **Read "Status and limitations" first.** The auction logic is tested. The Minecraft-facing layer
> (GUI, chat hooks) was written for **Minecraft 26.3** from migration documentation, but **could not be
> compiled or run in the environment that produced it**, so expect to fix a small compile error or two on
> first build. Every version-sensitive call is isolated in `mc/McCompat.java` to make that quick.

## 1. What is technically possible on DonutSMP

| Capability | Possible? | How this mod handles it |
|---|---|---|
| Server-side escrow, holding items or money | No. Needs a server plugin only DonutSMP admins can install | Not attempted |
| Moving items, taking payment, forcing a winner to pay | No | Manual: you hand the item over, the winner pays with `/pay` |
| Reading chat | Yes (client-side) | Bids and payment notices are parsed from chat |
| Verifying a payment automatically | **No.** The only DonutSMP API found is stats-only (`/api` key; money, shards, playtime, kills, deaths), with no payment-verification endpoint | Chat lines are recorded as **DETECTED (unverified)**. Only your explicit click makes a payment **CONFIRMED** |
| Posting announcements | Technically yes, but auto-sending chat is automation and spam risk | Announcements are **copied to your clipboard**; you paste and press Enter |

**Architecture chosen:** a client-only Fabric mod for **Minecraft 26.3 (Java 25)**. No server component,
no companion app, no network calls. One recent source reports DonutSMP's Java side accepting clients up to
26.3; I could not confirm that with DonutSMP itself, so check by joining before relying on it.

### Rules (check before use)

Based on the community-maintained DonutSMP wiki (the official Discord is authoritative if they differ):

- Hacked clients and anything giving an unfair advantage are banned, including macros, scripts, auto-clickers
  and mouse tweaks, and a mod need not be named to be banned. There is **no public whitelist** of allowed mods.
- This mod is built to stay clear of that: it **never sends chat or commands, never clicks, never moves or
  swaps items, and never automates input**. It only reads chat and (read-only) your inventory.
- The inventory picker displays your items; if you are unsure whether a custom GUI that previews inventory
  counts as an "inventory mod", **ask DonutSMP staff before using it**. You can avoid the picker entirely by
  using the "All items" tab.
- Trades are in-game currency only (real-money trading is banned). Scamming is permitted on the server,
  which is why this mod enforces *verify payment, then deliver*.

## 2. Install and build

Requirements: **Java 25**, Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API 0.165.0+26.3 (same Minecraft
version). Minecraft 26.x is unobfuscated, so mods built for 1.21.11 or older do not work on it and vice versa.

**Easiest: let GitHub build the jar for you (no Java or Gradle on your PC).**

1. Create a free GitHub account and a new **private** repository.
2. Unzip `donut-auction.zip` and upload everything inside the `donut-auction` folder to the repository
   (drag the files onto the repository page; include the hidden `.github` folder).
3. Open the **Actions** tab, choose **Build mod jar**, click **Run workflow**, and wait a few minutes.
4. Open the finished run, download the **donut-auction-jar** artifact, unzip it, and put
   `donut-auction-1.0.0.jar` into `.minecraft/mods/` together with Fabric Loader 0.19.5+ and Fabric API for 26.3.

If the run fails, open the failed step, copy the red error lines, and send them to me. They will almost
certainly be one of the small version-name fixes listed below, and I can correct the code for you.

**Most reliable way to build** (the Fabric toolchain moves fast):

1. Generate a fresh 26.3 project at <https://fabricmc.net/develop/template/>.
2. Copy this project's `src/` folder over the template's `src/` (delete the template's example classes).
3. Run `./gradlew build` with JDK 25.
4. Put `build/libs/donut-auction-1.0.0.jar` (not the `-sources` jar) in `.minecraft/mods/` next to Fabric API.

Or use the included `build.gradle` / `gradle.properties` directly (you need a Gradle wrapper; run `gradle wrapper`
with the Gradle version the Fabric template uses, 9.5.x at last report). It uses the `net.fabricmc.fabric-loom`
plugin with no mappings line. If a version does not resolve, update `gradle.properties` from
<https://fabricmc.net/develop/>.

**If the build fails to compile**, these are the likeliest spots, in order:

1. `mc/McCompat.java`: screen, chat, overlay and clipboard calls. 26.2 moved screens and the HUD into `Gui`/`Hud`,
   and 26.3 changed the clipboard class (now `new ClipboardManager().setClipboard(text)`).
2. `gui/*.java`: 26.1+ renamed rendering (`GuiGraphics` to `GuiGraphicsExtractor`, `render` to `extractRenderState`,
   `drawString` to `text`, `renderItem` to `item`). If any of those signatures differ, adjust to the compiler's hint.
3. `Keybinds.java`: 26.3 moved input from GLFW to SDL. Delete it and the two `Keybinds.` calls in
   `DonutAuctionClient` if needed; the `/dauction` command does the same job.
4. `Commands.java`: Fabric renamed `ClientCommandManager` to `ClientCommands` in 26.1.
5. `mc/ItemCapture.java`: the tooltip call (`getTooltipLines`) is the one item API I could not verify for 26.x.

Run the logic tests (no Minecraft needed): `./gradlew coreSelfTest` should print `167 passed, 0 failed`.

## 3. Using it

Open the hub with **`/dauction`** (or the **M** key by default, rebindable under Controls > Key Binds > Misc).
Commands are handled locally and never sent to the server.

| Command | Purpose |
|---|---|
| `/dauction` | Dashboard |
| `/dauction new` | Start the item picker |
| `/dauction status` | Running auctions in chat |
| `/dauction sim <chat line>` | Feed a fake chat line through the real pipeline (testing) |
| `/dauction parse <chat line>` | Show how a line would be read (calibration) |
| `/dauction debug on\|off` | Classify every bid/paid-looking line live |
| `/dauction block <name>` / `unblock` / `blocked` | Bidder blocklist |

### Running an auction

1. **Create:** pick an item (My inventory, or All items for anything else), set starting bid, minimum raise, and
   duration (30s / 1m / 5m / 10m presets or custom like `90`, `2m`, `1h`). Name, count, enchantments, lore and
   attributes are captured.
2. **Announce:** the start message is copied automatically. Paste it into chat yourself.
   Bidders type `bid 5m` (or `bid #7 5m` if several auctions run). Amounts accept `k/m/b/t` (`2.5m`).
3. **Watch:** the dashboard shows a live countdown, highest bid and bidder, and full history with
   timestamps. A local countdown and an action-bar line also run. Reminders appear at 60/30/10/5 s.
4. **Close:** at zero the highest valid bid wins. The result message can be copied from the auction screen.
5. **Settle (the safe order):**
   1. Winner runs `/pay <you> <amount>`.
   2. The mod shows the line as **DETECTED - needs verification**. Check your balance, then click **Confirm**
      (two clicks). If you were paid some other way, use **Confirm manually** and type what you really received.
   3. Hand over the item, then click **Mark delivered**.
   4. Only now does the auction become **COMPLETE** and appear under *Sold*.
6. **No-pay:** **Winner did not pay: next bidder** offers the item to the runner-up at *their* bid and
   auto-blocks the defaulter (configurable).

### Two ways for people to bid

Pick per auction on the New auction screen (the sidebar button "New bids: CHAT/PAY" sets the default):

- **Chat bids:** players type `bid 5m` (or `bid #7 5m`). Payment happens after the auction ends.
- **Pay-to-bid:** a `/pay` to you during the auction **is** the bid (their total payments for that auction).
  Highest total wins, and the winner has already paid, so you only verify your balance and deliver. Everyone
  else shows up under **Refunds owed**: send the money back with `/pay` yourself, then click **Mark refunded**.
  A pay-to-bid auction stays under *To settle* until every refund is recorded.

If an auction closes with **no bids** but someone paid you during it, open it: the mod lists those payments
and an **Award to <name>** button makes that person the winner at the amount they paid. The payment is still
only *detected* until you confirm it.

### Chat log (calibrating the chat patterns)

The **Chat log** section shows the last lines the mod saw, in green when read as a bid or payment and amber
when they look related but were not understood. If DonutSMP's bid or payment messages are amber, click
**Copy lines**, paste them to whoever is helping you, and the patterns in `settings.json` can be adjusted.
The log lives in memory only; it is never saved to disk or sent anywhere.

### Bidding rules enforced

Bids are decided strictly in the order the client receives them. A bid must meet the starting bid (first) or beat the high bid by at least
the minimum raise. Equal amounts: first received wins. Rejected: self-bids, blocked players, exact duplicates, bids after zero,
unreadable or ambiguous amounts (`1,5m` is **rejected**, never guessed), absurd amounts (`maxBid`). Rejected attempts are logged
(toggle "Show rejected bids"). Optional anti-sniping extends the timer on last-seconds bids.

## 4. Configuration

`config/donutauction/settings.json` is created on first run (restart the game after editing).

| Key | Default | Meaning |
|---|---|---|
| `restrictToHosts` | `["donutsmp"]` | Only act on servers whose address contains one of these. `[]` = any server (testing) |
| `defaultMinIncrement` | `1` | Default minimum raise |
| `durationPresetsSec` | `[30,60,300,600]` | Preset buttons |
| `antiSnipeSeconds` | `0` | If > 0, bids in the final N s reset the timer to N s |
| `maxBid` | `1e15` | Safety cap |
| `requirePaymentBeforeDelivery` | `true` | Block "Mark delivered" until payment is confirmed |
| `autoBlockOnForfeit` | `true` | Blocklist winners who do not pay |
| `paymentFromGameChannelOnly` | `true` | Ignore payment-looking lines typed by players |
| `bidPatterns` / `paymentPatterns` | see file | Regexes with named groups `user`, `amount`, optional `id` |
| `reminderSeconds`, `actionBarStatus`, `showRejectedBids`, `debug` | | UI feedback |

Data (`data.json`) is saved atomically about once a second after any change. A corrupt file is renamed, never deleted.

### Calibrating chat formats (important)

I could not verify DonutSMP's exact chat line formats. The defaults assume `Name » bid 5m`, `[Rank] Name: bid 5m`,
whispers like `[Name -> me] bid 5m`, and payment notices like `Name paid you $2.5m`. The patterns are **anchored at
the start of the line on purpose** so that a player typing `Bob » bid 100m` (which appears as `Mallory » Bob » bid 100m`)
cannot impersonate Bob. To calibrate: `/dauction debug on`, have a friend bid and pay, and adjust the regexes using
`/dauction parse <line>` until it matches.

## 5. Features needing server permission or manual action

- **Item delivery:** always manual (you hand the item over).
- **Payment verification:** always manual confirmation. No official payment API exists.
- **Holding funds/items in escrow, forcing payment, preventing bid-and-ghost fraud:** impossible client-side;
  needs a server plugin. Use the blocklist and the pay-first rule.
- **Verifying a bidder can afford their bid:** not possible without a server or an API; bids are unbacked claims.
- Auctions run by *other* players are not tracked; "Sold/Unsold" means your own auctions (sold to a bidder vs. no sale, cancelled, defaulted).

## 6. Status and limitations (honest disclosure)

- **Tested here (167 automated checks, all passing, run on JDK 21 since this environment had no JDK 25):** money parsing, chat parsing including spoof attempts, every
  bidding rule, simultaneous bids, closing, anti-snipe, payment matching/partial/overpay/unmatched, confirmation gating,
  delivery ordering, completion gating, forfeit/runner-up, reminders, and the chat-to-engine pipeline.
- **Not tested here:** compiling against real Minecraft 26.3/Fabric, the GUI, live chat events, and JSON save/load
  (Gson was unavailable offline). The MC layer was ported using the Fabric and NeoForge 26.x migration guides
  and type-checked only against stubs I wrote from them, which cannot catch a wrong or changed API name.
  Treat the first build as the real test, and use the checklist below.
- A compiled `.jar` is **not** provided because the build environment had no network access to download Gradle,
  Loom and the Minecraft jars.
- Bid latency: a bid typed just before zero can arrive after zero and will be rejected; the client clock decides.
- Payments from a different account than the winner show as UNMATCHED; use manual confirm on the right auction.
- Targets 26.3 only. 26.4 snapshots exist; the version-sensitive calls are in `McCompat.java` for an easier port.

## 7. Testing checklist

**Offline, no server needed** (set `restrictToHosts` to `[]`; use a singleplayer world; use `:` not `»`):

- [ ] Game starts on 26.3 with Fabric API; `/dauction` opens the dashboard; `config/donutauction/settings.json` exists.
- [ ] `+ New auction`: inventory tab lists your items with icons; selecting shows name/count/enchantments/lore.
- [ ] All items tab: search "elytra", "dragon", "spawner" finds items; count box works.
- [ ] Create a 30 s auction; announcement is on your clipboard; countdown ticks live; action bar shows it.
- [ ] `/dauction sim Alice: bid 1k` -> accepted; highest bidder shows Alice.
- [ ] `/dauction sim Bob: bid 1k` -> rejected (not higher); `/dauction sim Bob: bid 1,5m` -> ignored as unreadable.
- [ ] `/dauction sim Bob: bid 2k` -> new highest; history shows both with timestamps; "Show rejected" lists rejections.
- [ ] Wait for zero: winner = Bob; chat says so; auction moves from Active to Settle.
- [ ] `/dauction sim Bob paid you $2k` -> shows **DETECTED - needs verification**, NOT confirmed.
- [ ] Mark delivered is disabled until you confirm; Confirm needs two clicks; then Delivered; auction appears in Sold.
- [ ] A short payment (`Bob paid you $1k`) shows SHORT; `/dauction sim Carl paid you $5k` is UNMATCHED.
- [ ] Pay-to-bid: create one, `/dauction sim Alice paid you $600k`, then `Bob paid you $300k` -> Alice leads; after it ends the loser appears under Refunds owed.
- [ ] No-bid rescue: let a chat-bid auction expire, `/dauction sim Friend paid you $1m`, open the unsold auction -> Award button.
- [ ] Chat log shows your sim lines in green.
- [ ] Forfeit flow: winner does not pay -> runner-up offered at their bid, defaulter blocked (`/dauction blocked`).
- [ ] Restart the game mid-auction: it is still there and closes on time; completed records persist.
- [ ] Two simultaneous auctions: `bid 5k` without `#id` is reported as ambiguous; `bid #2 5k` works.

**On DonutSMP** (after checking the rules with staff): `/dauction debug on`, run a tiny auction with a friend, confirm their
real bid line and `/pay` notice parse (adjust regexes if not), pay a small amount, and confirm via your balance.
