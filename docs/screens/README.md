# Screenshots

The main README shows these five images. Take them on the real Fire TV (1920x1080) once the build is final:

| File | What to show | How |
|---|---|---|
| `home.png` | Home with Lumo, the four tiles, the QR panel and 2 players joined | `.\video\capture.ps1 -Shot -Scene home` |
| `phone.png` | The phone controller page (touchpad, text box, buttons), ideally with a quest running | Phone screenshot, or the desktop harness: `tools/gw.sh :couchlink:runPhoneHarness`, open `http://localhost:8080` in a phone-sized browser window |
| `cards.png` | A Simple English Wikipedia article (e.g. Saturn) turned into cards | `.\video\capture.ps1 -Shot -Scene cards` |
| `quest.png` | Speed Safari with 2 finds, the evidence quote and the scoreboard | `.\video\capture.ps1 -Shot -Scene quest` |
| `rescue.png` | The rescue offer after fast hopping (demo mode on) | `.\video\capture.ps1 -Shot -Scene rescue` |

Keep site logos out of frame (scroll past the page header) so the screenshots carry only our own UI and the article text.
