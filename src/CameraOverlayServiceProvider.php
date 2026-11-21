<?php

namespace Madfortech\CameraOverlay;

use Illuminate\Support\ServiceProvider;

class CameraOverlayServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(CameraOverlay::class, fn () => new CameraOverlay);
    }

    public function boot(): void
    {
        //
    }
}
