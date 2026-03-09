# War3 Advanced Model Viewer

JavaFX desktop application (Gradle, JDK 21) for browsing Warcraft 3 `.mdx` files with thumbnail-style 3D previews.

## Current bootstrap scope

- Folder picker + directory tree explorer
- Recursive `.mdx` scan inside selected folder
- Search filter by model name
- Thumbnail card grid with per-model 3D preview
- Texture lookup from model material/texture references
- Texture loading support for `.blp`, `.tga`, and standard image files

This project reuses MDX parser sources from:
`C:\Users\Jaccouille\Documents\Work\Java\TReterasModelStudio`

## Requirements

- JDK 21 installed (Gradle toolchain targets Java 21)
- Internet access the first time Gradle downloads dependencies and wrapper distribution

## Run

```powershell
./gradlew run
```

## Build

```powershell
./gradlew build
```
