# Minigames
Server-side NeoForge 1.21.1 mod by Ex. Java 21. Capture is the first game.

Create and populate vanilla scoreboard teams, then use /capture center, /capture radius 10, /capture team add red, /capture team add blue, /capture start.
Other commands: /capture height, duration, boundary, team remove, team list, pause, resume, reset, status, stop.
Global commands: /minigames list, status, stopall.
All commands require permission level 2. Settings persist in the world capture.json file; active rounds do not survive restart. Stop before editing settings.
Build with Gradle 8.12.1 and Java 21. GitHub Actions uploads the JAR after compilation and rule tests.
