Third-party notices for the bundled Skija renderer

Sky-Myce includes HumbleUI Skija 0.143.17 and HumbleUI Types 0.2.0.
Their Apache 2.0 license is reproduced in Apache-2.0.txt; Skija's
upstream attribution is reproduced in Skija-NOTICE.txt.

The packaged native libraries are skija.dll (Windows x64), libskija.so
(Linux x64 and arm64), and libskija.dylib (macOS x64 and arm64). The
Windows dependency also includes ICU's icudtl.dat. These libraries
contain Skia and the applicable linked native components listed below.
Their licenses do not change Sky-Myce's own license.

Pinned upstream build provenance:
- Skija tag 0.143.17: 66d99dd9a977ca72b5f99a4ee43a16567f35241c.
- Skija script/build.py selects Skia build m143-da51f0d60e-4.
- Skia source: da51f0d60ea2b14e845a823dc11b405dbeef42d8.
- Skija platform/CMakeLists.txt and platform/cmake/FindSkia.cmake link
  Skia's paragraph, shaping, Unicode, resource, SVG, and animation
  modules, ICU, HarfBuzz, and FreeType. Linux uses system FreeType and
  Fontconfig; Windows and macOS use bundled FreeType.
- SkiaBuild script/build.py plus the pinned Skia gn/skia.gni/BUILD.gn
  enable Expat, libjpeg-turbo, libpng, libwebp, zlib, Wuffs, and Vulkan
  memory allocation. Windows also enables D3D12 memory allocation and
  SPIRV-Cross; Linux/macOS enable the DNG SDK and Piex raw-image codec.
- Skia's skcms color-management code uses Skia's BSD-3-Clause license.

The adjoining files preserve upstream license/notice text. SOURCES.txt
records pinned source URLs and SHA-256 hashes for those texts. The
FreeType-fthash notice is the original opening copyright/permission
comment from the pinned src/base/fthash.c, without its implementation.

Acknowledgments:
Portions of this software are copyright © 1996-2024 The FreeType
Project (https://freetype.org). All rights reserved. The bundled
FreeType component is distributed under the FreeType License (FTL).

This software is based in part on the work of the Independent JPEG Group.

This product includes DNG technology under license by Adobe Systems
Incorporated.
