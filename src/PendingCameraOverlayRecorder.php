<?php

namespace Madfortech\CameraOverlay;

use Illuminate\Support\Str;
use Native\Mobile\Events\Camera\VideoCancelled;
use Native\Mobile\Events\Camera\VideoRecorded;

class PendingCameraOverlayRecorder
{
    protected ?string $id = null;

    protected string $eventClass = VideoRecorded::class;

    protected bool $started = false;

    /**
     * @param  array<string, mixed>  $options
     */
    public function __construct(protected array $options = [])
    {
        //
    }

    public function id(string $id): self
    {
        $this->id = $id;

        return $this;
    }

    public function getId(): string
    {
        if ($this->id === null) {
            $this->id = (string) Str::uuid();
        }

        return $this->id;
    }

    public function script(string $script): self
    {
        $this->options['script'] = $script;

        return $this;
    }

    public function maxDuration(int $seconds): self
    {
        $this->options['maxDuration'] = $seconds;

        return $this;
    }

    /**
     * Seconds to count down before recording actually starts.
     */
    public function countdown(int $seconds): self
    {
        $this->options['countdown'] = max(0, $seconds);

        return $this;
    }

    /**
     * Lock the overlay camera to portrait or landscape recording.
     */
    public function orientation(string $orientation): self
    {
        $this->options['orientation'] = strtolower($orientation) === 'landscape' ? 'landscape' : 'portrait';

        return $this;
    }

    public function start(): bool
    {
        if ($this->started) {
            return false;
        }

        $this->started = true;

        if (! function_exists('nativephp_call')) {
            return false;
        }

        $payload = array_merge($this->options, [
            'id' => $this->getId(),
            'event' => $this->eventClass,
            'cancelEvent' => VideoCancelled::class,
        ]);

        $result = nativephp_call('CameraOverlay.RecordVideo', json_encode($payload));

        if (! $result) {
            return false;
        }

        $decoded = json_decode($result, true);

        return is_array($decoded) && ($decoded['status'] ?? null) === 'success';
    }

    public function __destruct()
    {
        if (! $this->started) {
            $this->start();
        }
    }
}
