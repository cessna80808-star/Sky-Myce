# Sky-Hawk Feature Overview

This document gives a concise overview of the current feature set in Sky-Hawk, organized by gameplay area.

## General Quality of Life

### Daily Digest
- Cached daily overview for skyblock news, mayor/event updates, personal dailies, and optional RNG activity.
- Accessible through `/sm digest`.
- Designed to keep useful daily information in one place without opening several menus.

### Client Info Overlay
- Displays session and client-side information in a compact HUD widget.
- Keeps useful status information visible while playing.

### Skill XP Widget
- Moves XP gain notifications out of the action bar and into a cleaner widget.
- Makes leveling progress easier to monitor at a glance.

### Ability Cooldown Widget
- Shows the cooldown for the currently held item.
- Helps with rotation timing and combat efficiency.

### Auto Refill
- Automatically refills item stacks from your sack when low.
- Reduces interruptions during grinding and progression.

### Party Commands
- Adds party-oriented commands using `!` prefixed actions.
- Makes coordination faster and more consistent in group play.

### Stash Helper
- Highlights low-value or junk stash items.
- Prevents accidental clicks on worthless items and speeds up sorting.

### Wardrobe and Loadout Keybinds
- Lets you bind rapid equipment swaps while in Wardrobe or Loadout screens.
- Speeds up setup changes between builds.

### Waypoints
- Adds utility waypoints for useful in-game locations or navigation aids.
- Helps players move more efficiently through the world.

### Personal Best Command
- Provides local personal-best lookup support via `/m7pb [player]` or `/sm m7pb`.
- Uses cached stats and existing SkyBlock providers where available.

### Auction House Tools
- Includes auction browsing and indexing features.
- Supports better searching, filtering, and browsing of auction data.

## Dungeons and Instance Features

### Party Finder Matcher
- Dims chest listings that do not match your selected classes or floor-specific personal best requirements.
- Keeps rows visible and clickable without auto-joining parties.

### Missing Players Notification
- Warns when players are missing when entering or joining an instance.
- Helps avoid poorly organized runs.

### Auto Requeue
- Requeues you automatically after an instance ends.
- Reduces friction when immediately farming or chaining runs.

### Dungeon Win Screen
- Shows a cleaner, animated post-run summary when a dungeon ends.
- Makes run completion more readable and satisfying.

### Dungeon Chat Filtering
- Filters distracting or noisy dungeon chat messages.
- Helps you focus on useful instructions and progress updates.

### Safe Chest Reroll Protection
- Prevents accidental rerolls on valuable dungeon chests.
- Provides configurable safeguards for chest safety.

### Dungeon Chest Profit Calculator
- Displays chest item values while opening dungeon chests.
- Helps players make quicker and more informed decisions.

### Chest Protection / Safety Checks
- Implements checks that protect chest interactions when valuable loot is present.
- Reduces avoidable mistakes during dungeon progression.

### Dungeon Friends and Party Coordination
- Lets players track online friends, class coverage, Catacombs levels, floor eligibility, and PB information.
- Adds quick party invites and message flows for dungeon coordination.

### Dungeon Finder / LFG
- Provides an in-mod flow for checking friend and party availability.
- Supports easier dungeon group planning without manual chat spam.

### Dungeon Tracker
- Tracks dungeon runs, loot, RNG, and progression data.
- Includes overview, loot tables, timeline, and collection views.
- Supports floor, mode, date, and session filters.

### Dungeon Tracker Widget
- Shows summary information for the dungeon tracker while in the dungeon hub or after a run.
- Keeps key stats visible without opening extra menus.

### Chest Tracker
- Tracks chest-related progression and rewards.
- Helps monitor what has been acquired during dungeon runs.

### Floor Tracking and Dungeon Timing
- Tracks floors and timing data for dungeon progress and performance analysis.
- Useful for run review and optimization.

## Social and Party Planning

### Friend-Based Dungeon Coordination
- Tracks who is online, who is eligible for which floors, and what classes they can cover.
- Helps streamline group formation and run planning.

### Relay-Backed Messaging
- Supports `/sm msg <ign> <message>` and `/sm reply` / `/sm r` for private friend conversations.
- Adds acknowledgement handling and chat routing features.
- Supports `/sm chat <ign>` to route typed chat to a specific friend until a channel switch occurs.

### Cosmetic Names and Visual Cosmetics
- Displays cosmetic names from cached friends, party members, and chat senders.
- Supports authenticated visual player cosmetics with configurable size and positioning.
- Requires proper relay/Discord setup in supporting environments.

### Social Screen Features
- Includes friend and social UI support for coordinating with party members and friends.
- Combines status, invite, and messaging flows in one place.

## HUD and Visual Utility

### HUD Components
- Provides modular HUD widgets for tracking information while playing.
- Supports display customization and layout controls.

### HUD Theme System
- Lets players customize HUD colors, opacity, and layout behavior.
- Includes inherited global and widget themes.

### Theme Editor
- Gives players a visual editor for adjusting HUD appearance.
- Helps create more readable, personalized interfaces.

### Widget Editor
- Lets users edit and manage widget placement and properties.
- Supports easier UI tuning without manually editing config.

## Mining and Miscellaneous Features

### Mining Features
- Includes mining-related utilities and tracking for relevant gameplay loops.
- Helps keep mining activity easier to monitor and optimize.

### Cosmetics System
- Adds user-facing cosmetic and styling controls for social features.
- Includes cosmetic profile management and display customizations.

### Troll / Experimental Features
- Includes minor playful or experimental utilities in the mod codebase.
- These are not core gameplay features and are kept isolated.

## Loader and Startup Experience

### Sky-Myce Loader
- Provides a loader flow for account setup and verified installation.
- Helps ensure the game launches with the correct setup and environment.

### Startup Authentication and Setup
- During startup, the loader can confirm installation and launch state.
- Supports automatic updates without manual restart in the configured flow.

### Authorization and Installation Workflow
- Handles verified setup and account flow for game startup.
- Keeps mod installation and updating more stable and self-guided.

## Feature Summary

Sky-Hawk combines:
- dungeon automation and safety tools,
- party and friend coordination features,
- HUD and overlay utilities,
- daily tracking and personal progress views,
- auction and listing tools,
- cosmetic and social customization,
- startup / loader support to keep the mod experience smooth and reliable.

The overall goal is to reduce friction in SkyBlock by surfacing important information, automating repetitive actions, and improving communication and efficiency without cluttering the game experience.
