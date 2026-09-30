# Demo "cli" (spec section 13.3). Key moments: Screenshot lines; narration: # say: lines.
Set Shell "bash"
Set FontFamily "JetBrains Mono"
Set FontSize 16
Set Framerate 20
Set Width 1400
Set Height 820
Set Padding 20
Set Theme "Catppuccin Mocha"
Set WaitTimeout 30s
Set TypingSpeed 40ms

Hide
Type "export PS1='$ '; clear"
Enter
Sleep 500ms
Show

# say: In the terminal, scan prints the same report, in full color.
Type "skill-atlas scan github.com/acme/agent-skills --filter pdf"
Enter
Wait+Screen /2 of 8 skills match/
Sleep 3500ms
Screenshot screenshots/01-rich.png
Sleep 1s

# say: Pipe it anywhere, and you get clean plain text for your scripts.
Type "clear; skill-atlas scan github.com/acme/agent-skills 2>/dev/null | head -14"
Enter
Wait+Screen /Found 8 skills/
Sleep 3500ms
Screenshot screenshots/02-plain.png
Sleep 1s

# say: Copies in dot agents and dot claude are merged, and skills shipped in the product are labeled.
Type "clear; skill-atlas scan github.com/acme/workbench 2>/dev/null | sed -n '5,22p'"
Enter
Wait+Screen /also in plugins/
Sleep 5500ms
Screenshot screenshots/03-merged.png
Sleep 1s

# say: Test fixtures aren't skills, so they're set aside.
Type "clear; skill-atlas scan github.com/acme/agent-framework 2>/dev/null | tail -5"
Enter
Wait+Screen /calculator-tool/
Sleep 3s
Screenshot screenshots/04-fixtures.png
Sleep 1s

# say: Zoom into one skill: its description, similar skills, and the file itself.
Type "clear; skill-atlas scan github.com/acme/agent-skills --skill pdf-toolkit 2>/dev/null | sed -n '5,24p'"
Enter
Wait+Screen /Similar skills:/
Sleep 4500ms
Screenshot screenshots/05-skill.png
Sleep 1s

# say: And several repositories in one go, with repo colon to narrow it down.
Type "clear; skill-atlas scan github.com/acme/agent-framework github.com/acme/agent-skills --filter 'repo:framework platform'"
Enter
Wait+Screen /Scanned 2 repositories/
Sleep 4500ms
Screenshot screenshots/06-several.png
Sleep 1s
