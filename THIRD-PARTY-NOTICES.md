# Third party notices

gt6scan itself is MIT licensed, see [LICENSE](LICENSE).

None of the components below is bundled into this repository or into the built mod
jar. They are dependencies that Gradle pulls in at build time and that the user
installs into the game next to this mod, so they stay under their own licenses and
their copyright stays with their authors. This file only records which license each
of them uses and where it comes from.

## Build and runtime dependencies

| Component | License | Upstream |
| --- | --- | --- |
| GregTech 6 (`gregapi`, Gregory Techneticies) | LGPL-3.0 | <http://gregtech.mechaenetia.com/> |
| ModularUI2 (CleanroomMC / GTNewHorizons) | LGPL-3.0 | <https://github.com/GTNewHorizons/ModularUI2> |
| JourneyMap 1.7.10 (Techbrew LLC) | LGPL-2.1 | <https://github.com/TeamJM/journeymap> |
| GTNHLib (GTNewHorizons) | LGPL-3.0 | <https://github.com/GTNewHorizons/GTNHLib> |
| NotEnoughItems / CodeChickenCore (GTNewHorizons) | see upstream | <https://github.com/GTNewHorizons/NotEnoughItems>, <https://github.com/GTNewHorizons/CodeChickenCore> |

GregTech 6's license text is `LICENSE` (code under `COPYING.LESSER`, assets under
`COPYING.assets`, logos under `COPYING.logos` in its own repository). JourneyMap
ships both `LICENSE` and the GNU LGPL 2.1 text; its in-jar `license.txt` states
"Copyright (c) 2011-2017 Techbrew LLC <techbrew.net>. LGPL v2.1".

Because these are regular mod dependencies and their code is not copied into this
mod, this project's own code can stay MIT. When redistributing, keep those libraries
as separate files, keep their license texts, do not add restrictions on top of them,
and point users to where their corresponding source can be obtained.

## Optional client mods

InputFix (`lain.mods.inputfix`, zlainsama) is licensed under the
[Minecraft Mod Public License 1.0.1](https://github.com/zlainsama/inputfix), source at
<https://github.com/zlainsama/InputFix>. It is **not** part of this repository and is
**not** shipped with the mod jar: it is an optional client side prerequisite for IME
(Chinese/Japanese/Korean) input in the search box, next to lwjgl3ify which provides
the same character events. If you want to test IME input locally, build it yourself
and drop the jar into `libs/` - see [libs/README.md](libs/README.md); that path is
git-ignored on purpose so no third party binary is ever redistributed from here.
