# Codriod UI concepts

## Current visual direction: VS Code blue, dark and light

The requested VS Code Dark+ / Light+ inspired themes replace the original purple direction for future UI work. Screen layouts remain the same. These are generated design concepts, not app screenshots or exact pixel reproductions of VS Code.

| Board | Dark | Light |
| --- | --- | --- |
| Projects, Files/Editor, Terminal, Run/Preview | [Dark core](03-vscode-dark-core.png) | [Light core](04-vscode-light-core.png) |
| AI Agents, Extensions, Runtimes | [Dark add-ons](05-vscode-dark-addons.png) | [Light add-ons](06-vscode-light-addons.png) |

| Color role | Dark | Light |
| --- | --- | --- |
| Editor / terminal | `#1E1E1E` | `#FFFFFF` |
| Sidebar / sheets | `#252526` | `#F3F3F3` |
| Header / inactive tabs | `#2D2D30` | `#ECECEC` |
| Main text | `#D4D4D4` | `#333333` |
| Borders | `#3C3C3C` | `#D4D4D4` |
| Accent / primary buttons | `#007ACC` | `#007ACC` |
| Selected row | `#094771` | `#D6EBFF` |

These values are the intended implementation tokens; generated raster pixels can vary. Exact [theme-edit prompts](VSCODE-THEME-PROMPTS.md) are saved alongside the images. App source files were not changed.

## Original purple concepts

Seven proposed phone screens based on the saved [product plan](../moavia-code-studio-build-plan.md).

- [Core workspace](01-core-workspace.png): Projects, Files/Editor, Terminal, Run/Preview.
- [Agents and add-ons](02-agents-and-addons.png): AI Agents, Extensions, Runtimes.
- [Generation prompts](PROMPTS.md): exact prompts used with built-in imagegen.

The compact dark theme follows the plan's palette, editor-first layout, terminal sheet, bottom tools, and AI edge handle. These are visual references; sample output, runtime install states, remote workspaces and provider logins do not demonstrate implemented or tested capabilities.

Images were generated on 2026-11-08. Generated copy and icons may need adjustment during implementation. App and terminal source files were not changed for these concepts.
