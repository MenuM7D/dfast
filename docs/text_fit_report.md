# Text fit report (offline measurement)

Measured with the real IBM Plex Sans Arabic Medium file, at 14sp and at 1.3x system text size, for the side menu and More page labels, in every language folder under dfast/app/src/main/res (1942 label/size samples).

- Side menu room for text: about 212dp (300dp wide, minus padding, icon and gap). Side menu items now allow 2 lines.
- At normal text size, 1 label is wider than one line: French "download_queue" (213dp). It wraps to two lines.
- At 1.3x, 4 labels need two lines: fr download_queue, lv kill_app, fil command_templates, pl kill_app. All fit in two lines.
- More page rows wrap freely and grow in height.
- The font has no glyphs for Cyrillic, Greek, Hebrew, Thai, CJK, Indic scripts, and some accented Latin letters (Turkish, Polish, Czech and others). Android draws those characters with the phone's system font. No empty boxes, but the look is mixed in those languages.
