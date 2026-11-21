<?php

namespace Madfortech\CameraOverlay\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static \Madfortech\CameraOverlay\PendingCameraOverlayRecorder recordVideo(array $options = [])
 * @method static bool saveToGallery(string $path)
 *
 * @see \Madfortech\CameraOverlay\CameraOverlay
 */
class CameraOverlay extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Madfortech\CameraOverlay\CameraOverlay::class;
    }
}
