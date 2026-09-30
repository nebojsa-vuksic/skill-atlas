# Tour 5 of 6: the interactive browse view (spec sections 5.8 and 13.5).
Require skill-atlas
Set Shell "bash"
Set FontSize 16
Set Framerate 20
Set Width 1400
Set Height 820
Set Padding 20
Set TypingSpeed 60ms
Set Theme "Catppuccin Mocha"

# say: Prefer to explore? Browse opens a full screen view of any repository.
Type "skill-atlas browse github.com/anthropics/skills"
Enter
Sleep 7s

# say: Arrow keys move through the skills, and the details follow along.
Down
Sleep 900ms
Down
Sleep 900ms
Down
Sleep 900ms
Down
Sleep 2s

# say: Slash to filter. It narrows with every key.
Type "/"
Sleep 600ms
Type "pdf"
Sleep 1s
Enter
Sleep 3s

# say: Tab jumps into similar skills, and Enter opens one.
Tab
Sleep 1500ms
Down
Sleep 1s
Enter
Sleep 2s

# say: Escape clears the filter, and q hands your terminal right back.
Escape
Sleep 2s
Type "q"
Sleep 3s
