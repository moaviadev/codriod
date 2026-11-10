# Projects and storage

## Where projects are saved

- **With storage access (recommended):** each project is a normal folder in **Internal storage/Codriod/**, next to
  Download, DCIM and other folders. File managers, other apps and a computer over USB can open it.
  - Android 11 and later: Codriod asks for "All files access" in a settings page.
  - Android 10 and older: Codriod uses the normal storage permission.
- **Without storage access:** projects stay in Codriod's private storage, as before. A banner on Home offers to allow
  access, and later to **move older projects** to Internal storage/Codriod.
- **Opened folders (Open folder):** the files are edited **in place**, in the folder you picked. With storage access,
  Codriod works on the real path. This is fast, and the terminal, Git, Ubuntu and Codriod AI all work there. Without
  storage access it uses Android's document picker.
- **Deleting:**
  - A project Codriod created or cloned is deleted together with its folder in Internal storage/Codriod.
  - For an opened folder, Codriod only forgets the link and the folder stays.
- **Project settings** (name, last opened) stay in Codriod's private storage, so project folders contain only your files.
- **Add-ons** (Ubuntu, Node.js, AI CLIs, agent chats) live in Codriod's own app storage.

## Images in the editor

Tapping an image in Files opens it in the image viewer:

- **Formats:**
  - PNG, JPG, GIF (animated), WebP, BMP, ICO and SVG
  - HEIC/AVIF on Android 9 and later
- **Zooming:** pinch to zoom, drag to move, and double-tap to zoom in or back. The % chip resets the zoom.
- **Background:** checkerboard, dark or light, to see transparency.
- **Information:** the format, size in pixels and file size.
- **✦ Ask Codriod AI** attaches the image to the agent, for example "make this icon round" or "build this screen".
- **</> Edit code** opens an SVG as text.
- If Codriod AI or the terminal changes an image, the viewer reloads it.
