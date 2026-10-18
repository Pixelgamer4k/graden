# Device testing

Graden's video pipeline was tested on real phones and tablets through
[Samsung Remote Test Lab](https://developer.samsung.com/remote-test-lab), covering every major video engine family:
Samsung Exynos, Qualcomm Snapdragon and MediaTek Dimensity, flagship to budget.

Two small test apps were used, built from Graden's own engine with no user interface:

- **DevCheck** lists every encoder and decoder, then probes what each really does: 8 / 10-bit, P010 input, HDR10 and
  HLG metadata, 10-bit drawing from the GPU, and the levels that reach the file.
- **GradenLab** runs Graden's real import, proxies, stabiliser and export on test clips: H.264 and H.265, SDR, HLG and
  PQ, 1080p and 4K.

## Results (0.1 engine)

4K exports of a 2-second clip, H.265. Every export on every device ran on the **hardware encoder**: no software
fallback was seen.

| Device | Chip | GPU | 4K SDR | 4K HLG | Notes |
|---|---|---|---:|---:|---|
| Galaxy Z Flip8 | Exynos 2600 | Xclipse 960 | 67 fps | 23 fps | fastest tested |
| Galaxy Z Flip7 | Exynos 2500 | Xclipse 950 | 62 fps | 21 fps | |
| Galaxy S25 FE | Exynos 2400 | Xclipse 940 | 62 fps | 19 fps | |
| Galaxy Z Fold8 | Snapdragon 8 Elite Gen 5 | Adreno 840 | 24 fps | 10 fps | |
| Galaxy S25 Edge | Snapdragon 8 Elite | Adreno 830 | 23 fps | 12 fps | |
| Galaxy S23 | Snapdragon 8 Gen 2 | Adreno 740 | 16 fps | 8 fps | plain encoder writes no HDR10 metadata; the `.hdr` one does |
| Galaxy Tab S11 Ultra | Dimensity 9400+ | Mali-G925 | 24 fps | 12 fps | |
| Galaxy Tab S10 Ultra | Dimensity 9300+ | Mali-G720 | 21 fps | (SDR) | encoder lists only 8-bit, but makes true 10-bit when asked |
| Galaxy F23 | Snapdragon 750G | Adreno 619 | 4K30 limit | none | older encoders: no hardware HDR or AV1 |

## What 0.2 changed because of these tests

- **HDR export ~3× faster.** 0.1 read every 10-bit frame back from the GPU (20–40 ms a 4K frame). 0.2 draws it
  straight into the encoder (10-bit RGBA1010102 surface), with the old way kept as a fallback. On Snapdragon 8 Gen 2:
  4K HLG went from 10 to 30 fps, with the same picture (every colour within one 10-bit step).
- **HDR10 metadata everywhere.** For HDR, the encoder built for HDR editing is picked automatically.
- **MediaTek 10-bit.** Encoders that do Main 10 without listing it are offered it, and the file is checked.
- **Clear messages** when a phone cannot export HDR, instead of silently writing SDR.
- **HLG detection.** HLG footage was being treated as PQ.
- **Shaders cached** (they took 1–3 s to build before every export on Adreno and Mali).
- **No extra anti-aliasing pass** unless a clip is rotated (about 11 ms a 4K frame on Adreno and Mali).
- **Smooth editing proxies on MediaTek**, whose H.264 encoder has no constant-bitrate mode.
