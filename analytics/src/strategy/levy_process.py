"""
Lévy α-Stable process utilities for trend-following.

Mathematical basis
──────────────────
Log-returns are modelled as realisations of a Lévy α-stable process L_α(t):

    r_t ~ S(α, β, σ, μ)

where
  α ∈ (0, 2]  stability index  — controls tail heaviness
              (α=2 → Gaussian; α≈1.5 → typical equity heavy tail)
  β ∈ [-1,1]  skewness parameter
  σ > 0       scale (analogous to std for Gaussian)
  μ           location / drift

The drift μ and stability α together drive the trend signal and the adaptive
decay in the MomentumCalculator.
"""

import numpy as np
from typing import Tuple

_ALPHA_MIN = 0.5
_ALPHA_MAX = 2.0
_SCALE_FLOOR = 1e-10

# Precomputed IQR→scale conversion grid (McCulloch 1986 approximation)
_ALPHA_GRID = np.array([0.5, 1.0, 1.5, 1.7, 2.0])
_IQR_FACTOR_GRID = np.array([1.000, 1.585, 2.098, 2.316, 2.698])


# ── parameter estimation ────────────────────────────────────────────────────────

def estimate_stability_index(returns: np.ndarray, tail_fraction: float = 0.10) -> float:
    """Hill estimator for the tail index α from absolute log-returns."""
    n = len(returns)
    k = max(int(n * tail_fraction), 2)
    sorted_abs = np.sort(np.abs(returns))[::-1]
    threshold = sorted_abs[k - 1]
    if threshold <= 0:
        return 1.5
    log_ratios = np.log(sorted_abs[:k] / threshold)
    alpha_hat = float(k / np.sum(log_ratios))
    return float(np.clip(alpha_hat, _ALPHA_MIN, _ALPHA_MAX))


def estimate_drift(returns: np.ndarray, alpha: float) -> float:
    """Lévy-trimmed mean: trim more aggressively when tails are heavier."""
    trim_pct = max(0.05, (2.0 - alpha) * 0.15)
    n = len(returns)
    trim_count = max(1, int(n * trim_pct))
    trimmed = np.sort(returns)[trim_count: n - trim_count]
    return float(np.mean(trimmed)) if len(trimmed) > 0 else float(np.median(returns))


def estimate_scale(returns: np.ndarray, alpha: float) -> float:
    """IQR-based scale estimate for the Lévy stable distribution."""
    q75, q25 = np.percentile(returns, [75, 25])
    iqr = q75 - q25
    if iqr <= 0:
        return float(np.std(returns) + _SCALE_FLOOR)
    iqr_factor = float(np.interp(alpha, _ALPHA_GRID, _IQR_FACTOR_GRID))
    return float(max(iqr / iqr_factor, _SCALE_FLOOR))


def fit_levy_parameters(returns: np.ndarray) -> Tuple[float, float, float]:
    """Return (alpha, drift, scale) from a returns array."""
    if len(returns) < 5:
        return 1.5, float(np.mean(returns)), float(np.std(returns) + _SCALE_FLOOR)
    alpha = estimate_stability_index(returns)
    drift = estimate_drift(returns, alpha)
    scale = estimate_scale(returns, alpha)
    return alpha, drift, scale


# ── weighting ────────────────────────────────────────────────────────────────────

def compute_levy_weights(returns: np.ndarray, alpha: float, scale: float) -> np.ndarray:
    """
    Down-weight tail outliers using the Lévy characteristic exponent.

    Weight for return r_i:  w_i ∝ 1 / (1 + |r_i / σ|^α)

    Outliers (|r_i/σ| >> 1) receive low weight → momentum signal is not
    distorted by single extreme moves (jumps / flash crashes).
    """
    if scale <= _SCALE_FLOOR:
        return np.ones(len(returns)) / len(returns)
    normalised = returns / scale
    tail_penalty = np.abs(normalised) ** alpha
    weights = 1.0 / (1.0 + tail_penalty)
    total = weights.sum()
    if total <= 0:
        return np.ones(len(returns)) / len(returns)
    return weights / total
