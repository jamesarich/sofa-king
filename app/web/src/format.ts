/** `1 h 52 min` for a length in milliseconds; empty when it is not known. */
export function length(ms: number | null | undefined): string {
    if (!ms || ms <= 0) return '';
    const minutes = Math.round(ms / 60_000);
    const hours = Math.floor(minutes / 60);
    if (hours === 0) return `${minutes} min`;
    return minutes % 60 === 0 ? `${hours} h` : `${hours} h ${minutes % 60} min`;
}

/** `1:02:03` or `2:03` for a position in seconds. */
export function position(seconds: number): string {
    const whole = Math.max(0, Math.floor(seconds));
    const h = Math.floor(whole / 3600);
    const m = Math.floor((whole % 3600) / 60);
    const s = String(whole % 60).padStart(2, '0');
    return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
}
