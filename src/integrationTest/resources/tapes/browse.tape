# Demo "browse" (spec section 13.3). Key moments: Screenshot lines; narration: # say: lines.
Set Shell "bash"
Set FontFamily "JetBrains Mono"
Set FontSize 16
Set Framerate 20
Set Width 1400
Set Height 820
Set Padding 20
Set Theme "Catppuccin Mocha"
Set WaitTimeout 30s
Set TypingSpeed 60ms

Hide
Type "export PS1='$ '; clear"
Enter
Sleep 500ms
Show

# say: Prefer to explore? Browse opens a full screen view of any repository.
Type "skill-atlas browse github.com/acme/agent-skills"
Enter
Wait+Screen /8 of 8/
Sleep 3500ms
Screenshot screenshots/01-open.png
Sleep 1s

# say: Arrow keys move through the skills, and the details follow along.
Down
Sleep 800ms
Down
Sleep 3500ms
Screenshot screenshots/02-moved.png
Sleep 1s

# say: Slash to filter. It narrows with every key.
Type "/"
Sleep 500ms
Type "pdf"
Sleep 600ms
Enter
Wait+Screen /2 of 8/
Sleep 1500ms
Screenshot screenshots/03-filtered.png
Sleep 1s

# say: Tab jumps into similar skills, and q hands your terminal back.
Tab
Wait+Screen /▸/
Sleep 2s
Screenshot screenshots/04-similar.png
Sleep 1s
Escape
Sleep 800ms
Type "q"
Sleep 1500ms
