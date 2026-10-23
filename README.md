# Sling Slam

An Android game: lob boulders at the hazards rolling toward you before they smash into your shield.

## How to play

- **Aim and fire:** touch inside the circle at the bottom and pull back. Power comes from how far you drag from where you first touched, so your finger can wander off the circle. Pulling left aims right, like a real sling. Drag back to your starting point to cancel.
- **Short and long shots:** short pulls throw low and fast, almost straight out, for hazards that are close. Long pulls lob high and far.
- **Read the side view:** the strip at the top shows the battlefield side-on: you on the left, the hazard coming from the right, the arc of every shot, and an ✕ where it landed. While you pull back, a blue dotted arc and marker show where that shot will land.
- **Reload:** after each throw the next boulder rolls off the pile on the right into the sling.
- **Shield:** anything bad that reaches your sandbags drains it. Smashing hazards restores some, and it slowly recharges on its own. When it hits zero the game is over.
- **Levels:** each level is a series of waves followed by a boss. Level 1 has 5 waves and each level adds one (up to 8). Waves grow from 1 hazard to as many as 4, and get a little faster with every wave and level. The final wave warns that the boss is next. Beat the boss for a level bonus (500 × level), +25 shield and a split shot. Then there's a 6-second break: a "level complete" banner, followed by "Get ready for level X" with a 3-2-1 countdown (the first level gets the countdown too). If the boss gets through, you take the damage and move on.
- **Hazards:**
  - Rolling boulder: round granite, rolls straight at you
  - Jagged rock: slower, takes two hits (a cracked one slows down)
  - Rock cluster: a loose clump of tumbling stones that weaves side to side
  - **Boss golem** (end of every level): a slow, hunched giant built from jagged stone slabs, with a health bar overhead. It takes 4 hits in level 1, 5 in level 2, 7 in level 3, and keeps climbing. From the second boss on it sways side to side as it advances, slowly at first, wider and faster each level, while its forward speed keeps rising too. When it arrives it stops, raises its arms, and every stray stone still on the field lifts off and swirls into it. During the fight, any shot that lands short of it gets sucked in too. Every 2 stones it absorbs add a hit, and each one makes it bigger. Shots that land past it are safe.
- **Bonuses** (hit them to collect):
  - Power cell case: +30 shield
  - Ammo crate: sling upgrade (faster reload, bigger impact, longer roll). Boulders roll a little after landing at first and farther with each upgrade, and can smash hazards they roll into. At sling level 5 they also keep rolling after a direct hit.
  - Gold bars: +500 points
- **On the field:** only missed boulders stay on the ground, and they fade 1.5 seconds after they stop rolling. Boulders knock each other around like billiard balls: the harder a stone is hit, the faster and farther it rolls, and a knocked stone can roll into a hazard and smash it. A hazard that runs into a stone lying on the ground sets it off: the stone explodes and destroys the hazard (except the boss, which absorbs stones).
- **Split shot:** tap the button left of the sling to arm the loaded boulder. The moment you throw it, it becomes 3 smaller rocks chained together that spread out sideways as they fly, covering a wider area. Each rock can smash a hazard. Every run starts with one, you earn another for each boss you beat, and you can hold up to 3.
- **Points** float up over what you smashed, then fly to the scoreboard and tick in when they arrive.
- **Streaks:** every 5 smashes in a row raises your points multiplier. Letting something through resets it.
- **Tutorial:** an interactive practice run plays the first time you start, and any time from **How to play** on the title screen.
- **Scoreboard:** keeps your last 20 runs (score, level, and whether a continue was used) with your high score featured at the top. Open it from the title screen or after a game.
- **Controls:** pause and sound are in the bottom-right corner, next to your thumb.
- **Menus:** the pause menu has Resume and End game, game over has Watch ad to continue (once per run, and not when you end the game yourself), Play again, Scoreboard and Menu, and the title screen has How to play, Scores and Exit.
- **Testing:** the − / + buttons at the right end of the score row: + jumps to the current level's boss (or the next level if the boss is out), − goes back a level. Remove them before release.
- **Ads (placeholders for now):** a banner strip is reserved at the very top, away from the controls. An interstitial plays before every 3rd run, and after the shield goes down you can watch a rewarded ad to continue with half shield (once per run). All ad calls go through `showAd()` in `GameView.kt`, ready for AdMob.

## Install on your phone

Every push to `main` builds the app on GitHub Actions and publishes it to the
[latest release](https://github.com/randaladams/slingslam/releases/tag/latest).
Open that page on your phone, download `SlingSlam.apk`, and open it to install
(Android will ask you to allow installs from your browser the first time).
New builds install over the old one and keep your best score.

## Build it yourself

Open the folder in Android Studio, or run:

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

## Code map

- `World.kt` — game rules and physics (no Android code; easy to test)
- `GameView.kt` — layout, touch input, screens, HUD
- `Sprites.kt` — shaded drawings of boulders, hazards, sandbags, sling
- `Backdrop.kt` — painted battlefield and sky (generated at startup)
- `SoundFx.kt` — sound effects (`res/raw/*.wav`), master volume
- `MainActivity.kt` — full-screen host activity

The repo includes a throwaway signing key (`app/debug.keystore`) so updates install over each other.
Make your own private key before publishing to the Play Store.
