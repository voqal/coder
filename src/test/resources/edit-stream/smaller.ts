function updateEloScores(scores: Record<string, number>, results: {first: string, second: string, outcome: number}[]) {
    const kFactor = 4;
    for (const result of results) {
        const { first, second, outcome } = result;
        const firstScore = scores[first] ?? 1000;
        const secondScore = scores[second] ?? 1000;

        const expectedScoreFirst = 1 + Math.pow(10, (secondScore - firstScore) / 400);
        const expectedScoreSecond = 1 + Math.pow(10, (firstScore - secondScore) / 400);
        let sa = 0.5;
        if (outcome === 1) {
            sa = 1;
        } else if (outcome === -1) {
            sa = 0;
        }
        scores[first] = firstScore + kFactor * (sa - expectedScoreFirst);
        scores[second] = secondScore + kFactor * (1 - sa - expectedScoreSecond);
    }
    return scores;
}
