## madfortech/camera-overlay

Records video with an in-app camera preview and a custom script overlay.

### PHP Usage

@verbatim
<code-snippet name="Record with script overlay" lang="php">
use Madfortech\CameraOverlay\Facades\CameraOverlay;

CameraOverlay::recordVideo()
    ->script($this->script)
    ->maxDuration(60)
    ->countdown(3)
    ->id('telescript-recording')
    ->start();

// Copy a recorded video into the device gallery
CameraOverlay::saveToGallery($absolutePath);
</code-snippet>
@endverbatim

### Events

- `Madfortech\CameraOverlay\Events\VideoRecorded` (path, mimeType, id)
- `Madfortech\CameraOverlay\Events\VideoCancelled` (cancelled, id)
