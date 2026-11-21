## telescript/camera-overlay

Records video with an in-app camera preview and a teleprompter script overlay.

### PHP Usage

@verbatim
<code-snippet name="Record with script overlay" lang="php">
use Telescript\CameraOverlay\Facades\CameraOverlay;

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

- `Native\Mobile\Events\Camera\VideoRecorded` (path, mimeType, id)
- `Native\Mobile\Events\Camera\VideoCancelled` (cancelled, id)
