package com.example.frauddetection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "fraud.rules")
public class FraudRulesConfig {

    private HighAmountConfig highAmount = new HighAmountConfig();
    private UnusualHourConfig unusualHour = new UnusualHourConfig();
    private HighRiskMerchantConfig highRiskMerchant = new HighRiskMerchantConfig();
    private RoundAmountConfig roundAmount = new RoundAmountConfig();
    private HighFrequencyConfig highFrequency = new HighFrequencyConfig();

    public HighAmountConfig getHighAmount() { return highAmount; }
    public void setHighAmount(HighAmountConfig highAmount) { this.highAmount = highAmount; }

    public UnusualHourConfig getUnusualHour() { return unusualHour; }
    public void setUnusualHour(UnusualHourConfig unusualHour) { this.unusualHour = unusualHour; }

    public HighRiskMerchantConfig getHighRiskMerchant() { return highRiskMerchant; }
    public void setHighRiskMerchant(HighRiskMerchantConfig highRiskMerchant) { this.highRiskMerchant = highRiskMerchant; }

    public RoundAmountConfig getRoundAmount() { return roundAmount; }
    public void setRoundAmount(RoundAmountConfig roundAmount) { this.roundAmount = roundAmount; }

    public HighFrequencyConfig getHighFrequency() { return highFrequency; }
    public void setHighFrequency(HighFrequencyConfig highFrequency) { this.highFrequency = highFrequency; }

    public static class HighAmountConfig {
        private boolean enabled = true;
        private double threshold = 10000.0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getThreshold() { return threshold; }
        public void setThreshold(double threshold) { this.threshold = threshold; }
    }

    public static class UnusualHourConfig {
        private boolean enabled = true;
        private int startHour = 0;
        private int endHour = 4;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getStartHour() { return startHour; }
        public void setStartHour(int startHour) { this.startHour = startHour; }

        public int getEndHour() { return endHour; }
        public void setEndHour(int endHour) { this.endHour = endHour; }
    }

    public static class HighRiskMerchantConfig {
        private boolean enabled = true;
        // ArrayList (not List.of) so Spring's property binder can populate it
        private List<String> categories = new ArrayList<>(List.of("GAMBLING", "CRYPTO", "FOREX"));

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public List<String> getCategories() { return categories; }
        public void setCategories(List<String> categories) { this.categories = categories; }
    }

    public static class RoundAmountConfig {
        private boolean enabled = true;
        private long multiple = 1000;
        private double minAmount = 5000.0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public long getMultiple() { return multiple; }
        public void setMultiple(long multiple) { this.multiple = multiple; }

        public double getMinAmount() { return minAmount; }
        public void setMinAmount(double minAmount) { this.minAmount = minAmount; }
    }

    public static class HighFrequencyConfig {
        private boolean enabled = true;
        private int maxCount = 5;
        private int windowMinutes = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getMaxCount() { return maxCount; }
        public void setMaxCount(int maxCount) { this.maxCount = maxCount; }

        public int getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(int windowMinutes) { this.windowMinutes = windowMinutes; }
    }
}
