import confetti from 'canvas-confetti';

// Single burst for new-score celebrations
window.triggerCelebration = function(durationMs) {
    fireworksShow(durationMs || 30000);
};

function fireworksShow(durationMs) {
    const end = Date.now() + durationMs;

    (function frame() {
        // Launch 1-2 bursts per frame from random positions
        const burstCount = 1 + Math.floor(Math.random() * 2);
        for (let i = 0; i < burstCount; i++) {
            confetti({
                particleCount: 5 + Math.floor(Math.random() * 15),
                startVelocity: 20 + Math.random() * 20,
                spread: 60 + Math.random() * 60,
                origin: {
                    x: 0.1 + Math.random() * 0.8,
                    y: 0.2 + Math.random() * 0.5
                },
                colors: randomPalette(),
                ticks: 200,
                gravity: 0.8 + Math.random() * 0.4,
                scalar: 0.8 + Math.random() * 0.4,
                drift: (Math.random() - 0.5) * 0.5
            });
        }

        if (Date.now() < end) {
            requestAnimationFrame(frame);
        }
    }());
}

function randomPalette() {
    const palettes = [
        ['#ff0000', '#ff6600', '#ffcc00'],           // fire
        ['#00ccff', '#0066ff', '#ffffff'],            // ice
        ['#ff00ff', '#cc00ff', '#ff66cc'],            // purple/pink
        ['#00ff00', '#66ff33', '#ccff00'],            // neon green
        ['#ffd700', '#ffec8b', '#ffffff'],            // gold
        ['#ff4500', '#ff6347', '#ffa500', '#ffd700'], // sunset
    ];
    return palettes[Math.floor(Math.random() * palettes.length)];
}
