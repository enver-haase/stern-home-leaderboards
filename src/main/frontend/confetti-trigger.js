import confetti from 'canvas-confetti';

window.triggerCelebration = function() {
    confetti({
        particleCount: 100,
        spread: 70,
        origin: { y: 0.6 }
    });
};
