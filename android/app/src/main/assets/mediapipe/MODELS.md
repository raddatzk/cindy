# MediaPipe models

Bundled so detection runs fully offline. Both are Google's published MediaPipe models under the
Apache License 2.0 (see their model cards). Re-fetch with `curl -L -o <file> <url>` and compare the
SHA-256.

| File | Source | SHA-256 |
| --- | --- | --- |
| `blaze_face_full_range.tflite` | https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_full_range/float16/latest/blaze_face_full_range.tflite | `3698b18f063835bc609069ef052228fbe86d9c9a6dc8dcb7c7c2d69aed2b181b` |
| `pose_landmarker_lite.task` | https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/latest/pose_landmarker_lite.task | `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a` |

Fetched 2026-09-16. Model cards:
- BlazeFace (full range): https://storage.googleapis.com/mediapipe-assets/MediaPipe%20BlazeFace%20Model%20Card%20(Full%20Range).pdf
- BlazePose GHUM 3D (pose landmarker): https://storage.googleapis.com/mediapipe-assets/Model%20Card%20BlazePose%20GHUM%203D.pdf

The full-range face model replaced the short-range one on 2026-09-16: the short-range model is
made for selfie distance (about 2 m), and the face at the top of a pull-up is further away.
