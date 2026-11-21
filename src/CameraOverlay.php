<?php

namespace Madfortech\CameraOverlay;

class CameraOverlay
{
    /**
     * @param  array<string, mixed>  $options
     */
    public function recordVideo(array $options = []): PendingCameraOverlayRecorder
    {
        return new PendingCameraOverlayRecorder($options);
    }

    /**
     * Copy a local video into the device gallery so it shows in the Photos/Gallery app.
     */
    public function saveToGallery(string $path): bool
    {
        if ($path === '' || ! function_exists('nativephp_call')) {
            return false;
        }

        $result = nativephp_call('CameraOverlay.SaveToGallery', json_encode(['path' => $path]));

        if (! $result) {
            return false;
        }

        $decoded = json_decode($result, true);

        return is_array($decoded) && ($decoded['status'] ?? null) === 'success';
    }
}
