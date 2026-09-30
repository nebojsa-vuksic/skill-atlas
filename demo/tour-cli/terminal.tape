# Tour 4 of 6: scan in the terminal (spec sections 4.4, 5.1, 5.2, 5.7, 5.10 and 13.5).
Require skill-atlas
Set Shell "bash"
Set FontSize 16
Set Framerate 20
Set Width 1400
Set Height 820
Set Padding 20
Set TypingSpeed 45ms
Set Theme "Catppuccin Mocha"

# say: In the terminal, scan prints the same report, in full color.
Type "skill-atlas scan github.com/anthropics/skills --filter pdf"
Enter
Sleep 7s

# say: Pipe it anywhere, and you get clean plain text for your scripts.
Type "clear; skill-atlas scan github.com/anthropics/skills 2>/dev/null | head -16"
Enter
Sleep 6s

# say: Big monorepo? Copies are merged, and skills shipped in the product are labeled.
Type "clear; skill-atlas scan github.com/JetBrains/MPS 2>/dev/null | sed -n '5,20p'"
Enter
Sleep 9s

# say: Test fixtures aren't skills, so they're set aside.
Type "clear; skill-atlas scan github.com/JetBrains/koog 2>/dev/null | tail -4"
Enter
Sleep 7s

# say: Zoom into one skill. The full story, similar skills, and the file itself.
Type "clear; skill-atlas scan github.com/anthropics/skills --skill pdf 2>/dev/null | sed -n '5,26p'"
Enter
Sleep 8s

# say: And yes, several repositories in one go, with repo colon to narrow it down.
Type "clear; skill-atlas scan github.com/JetBrains/koog github.com/anthropics/skills --filter 'repo:koog java'"
Enter
Sleep 10s
