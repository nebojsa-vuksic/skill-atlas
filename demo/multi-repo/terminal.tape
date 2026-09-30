# Terminal demo for several repositories with search (spec sections 5.10 and 13).
Output terminal.gif
Output terminal.mp4

Require skill-atlas

Set Shell "bash"
Set FontSize 16
Set Width 1400
Set Height 820
Set Padding 20
Set TypingSpeed 60ms
Set Theme "Catppuccin Mocha"

Hide
Type "clear"
Enter
Show

Type "skill-atlas scan github.com/JetBrains/koog github.com/anthropics/skills --filter 'repo:koog java'"
Sleep 500ms
Enter
Sleep 9s

Type "clear"
Enter
Type "skill-atlas scan github.com/JetBrains/koog github.com/anthropics/skills --skill split-jvm-nonjvm | sed -n '5,22p'"
Sleep 500ms
Enter
Sleep 9s
