# Sky-Myce Bazaar: quick tutorial

Sky-Myce suggests Bazaar flips and keeps your own profit history. It never places, changes, cancels or claims an order; you do every trade yourself.

## Your first flip

1. Join a SkyBlock island and make sure your purse shows on the scoreboard.
2. Run `/sm bazaar` (or open **Economy → Bazaar Trading**) and click **Start flipping**.
3. In **Flips**, pick **Steady**, **Balanced** or **Bold**. Each card shows the estimated profit after tax, the coins needed and a rough fill time. **Details** explains the numbers.
4. Click **Guide this flip**. The Bazaar search opens, the suggested amount is copied and the **Bazaar Assistant** starts guiding you. Place the buy order yourself.
5. Follow the **Next step** banner at the top of the terminal (or the HUD): claim the bought items, create a sell offer at the suggested price, then claim your coins.
6. Click **Stop flipping**, or run `/sm bazaar off`, when you're done. Leaving the island also stops the session; start it again yourself.

Sky-Myce reads Bazaar chat receipts (order set up, filled, claimed, cancelled) and the native **Manage Orders** menu. If something looks out of date, use **Sync orders** to open Manage Orders.

## The terminal

The four tiles show **Purse** (coins you can spend now), coins **in buy orders**, the **inventory cost** of items you hold, and **realized profit**. They are never added together.

| Tab | What it's for |
| --- | --- |
| **Flips** | Ranked suggestions for your purse and risk profile. |
| **Positions** | What you are flipping now and the next step for each, relist advice, and receipts that need your review. |
| **History** | Realized profit, ROI, completed sales and the leaderboard. Correct a personal record here. |
| **Replays** | Private recordings of each session. Watch them in the browser dashboard, send a summary, or publish one. |
| **More** | The Bazaar Assistant, alert toggles, the browser dashboard, your personal webhook, settings sync, export and delete. |

**All Bazaar settings** (under **More**) holds the finder filters, tax estimate, relist threshold, alert thresholds and cooldowns, keybinds and timezone.

## Bazaar Assistant

The assistant walks you through one flip at a time: buy order, claim, sell offer, claim. It never clicks, confirms or places anything; you do every step yourself.

- Start it with **Guide this flip**, **More → Guide the best flip**, `/sm bazaar assist`, or `]`. Stop it with `[` or `/sm bazaar assist stop`. Both keys also work inside menus; change them in **Options → Controls → Miscellaneous**.
- Its panel says what to do next. In menus it sits beside the menu (drag it anywhere to keep it there). The button to click is highlighted, but only when exactly one slot matches and still shows that button; otherwise nothing is highlighted and the panel explains why.
- In chat, the expected command appears as faint ghost text, and anything typed wrong turns red. Press **Tab** to fill it in. Other commands keep normal Tab completion.
- On Bazaar signs, the expected search, amount or price shows above the sign and turns green once what you typed matches. Then click **Done** (Enter only moves to the next line).
- **Price entry** chooses between matching the order book (Top Order +0.1 / Best Offer -0.1) and typing the recommended price.
- When a guided sale is claimed, its realized profit rises from the order you clicked, and the panel shows this run's profit per hour, from the same records as **History**. Stopping prints a short run summary.
- Highlight color, shape, thickness, opacity, pulse and animations are under **More → All assistant settings**. Move or resize the panel and profit pop-ups with **Move the HUD**. **Reset to defaults** restores everything.

Two helpers are off by default and explained before you turn them on:

- **Auto Command** sends the shown command when you press the **Send Bazaar Assistant command** key (unbound until you set it): one key press, one command, only with no menu open and never twice within three seconds.
- **Auto Sign** pre-types the expected value into an empty Bazaar sign. You still check it and click Done.

Hypixel's rules on client modifications apply to you. Leave both helpers off if you're unsure.

## Profit and records

- Realized profit = coins received after tax − what those items cost (first in, first out). Unsold items and forecasts never count.
- A filled sale counts once you claim its coins; until then it shows as waiting under **Positions**.
- **Needs your review** lists receipts that couldn't be matched to an order with certainty. **Sync orders** usually fixes them. **Fix record** saves a personal correction, which stays out of public rankings.
- The relist warning appears on the **seventh** relist of the same position by default.

## HUD, dashboard and alerts

- The **Bazaar Active Flip** HUD appears while flipping with live data and shows the position that needs you most. Move it with **More → Move the HUD**; cycle positions with the keybind or `/sm bazaar cycle`.
- `/sm bazaar dashboard` opens a private page on this computer only; it shuts down two minutes after you close or hide the page, or with `/sm bazaar close-dashboard`.
- Exceptional-flip alerts (in game, dashboard, Discord DM, personal webhook) only fire while flipping with live data, for flips you can afford.

## Privacy

Your trading history stays on this device. Only verified completed sales are shared to the Bazaar leaderboard, and only if your community sharing settings allow it. Publishing a replay is always a separate choice with redaction options; revoking a link or deleting local history never changes the other.
