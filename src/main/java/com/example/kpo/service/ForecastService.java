package com.example.kpo.service;

import com.example.kpo.dto.forecast.DemandPoint;
import com.example.kpo.dto.forecast.DemandSeries;
import com.example.kpo.dto.forecast.ForecastGranularity;
import com.example.kpo.dto.forecast.ForecastMetrics;
import com.example.kpo.dto.forecast.ForecastPoint;
import com.example.kpo.dto.forecast.ForecastResult;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ForecastService {

    private static final double[] ALPHA_GRID = {0.2, 0.4, 0.6, 0.8};
    private static final double[] BETA_GRID = {0.1, 0.2, 0.3, 0.4};
    private static final int MIN_HISTORY_POINTS = 5;
    private final DemandSeriesService demandSeriesService;

    // Инжектируем сервис, который предоставляет исторические ряды спроса по товару.
    public ForecastService(DemandSeriesService demandSeriesService) {
        this.demandSeriesService = demandSeriesService;
    }

    // Упрощенный вызов: прогноз по дням с базовой детализацией.
    public ForecastResult forecastProduct(Long productId,
                                          int historyDays,
                                          int validationWindow,
                                          int horizonDays) {
        return forecastProduct(productId, historyDays, validationWindow, horizonDays, ForecastGranularity.DAY);
    }

    // Главная точка входа: загружаем историю, подбираем параметры Holt и формируем прогноз.
    // История используется как обучающая выборка, прогноз строится на будущие дни/недели.
    public ForecastResult forecastProduct(Long productId,
                                          int historyDays,
                                          int validationWindow,
                                          int horizonDays,
                                          ForecastGranularity granularity) {
        if (horizonDays <= 0) {
            throw new IllegalArgumentException("Forecast horizon must be greater than 0");
        }

        //1. Определяем, сколько дней истории грузить
        int dailyHistoryDays = granularity == ForecastGranularity.WEEK
                ? historyDays * 7
                : historyDays;
        //2. Загружаем ДНЕВНОЙ временной ряд
        DemandSeries dailySeries = demandSeriesService.loadDailyDemandSeries(productId, dailyHistoryDays);

        //3. Агрегация: дни → недели
        DemandSeries series = granularity == ForecastGranularity.WEEK
                ? aggregateWeeklySeries(dailySeries, historyDays)
                : dailySeries;
        List<DemandPoint> history = series.points();

        //4. Проверка «данных достаточно?»
        boolean insufficient = series.insufficientData()
                || history.size() < MIN_HISTORY_POINTS;

        //5. Подбор параметров Holt (alpha / beta) →
        ForecastEvaluation evaluation = evaluateHoltParameters(history, validationWindow);
        double alpha = evaluation.alpha();
        double beta = evaluation.beta();
        ForecastMetrics metrics = evaluation.metrics().orElse(null);

        //6. Генерация финального прогноза →
        List<ForecastPoint> forecastPoints = insufficient
                ? List.of()
                : generateHoltForecast(history, horizonDays, alpha, beta, granularity);

        return new ForecastResult(
                productId,
                history,
                forecastPoints,
                metrics,
                alpha,
                beta,
                insufficient
        );
    }

    // Подбор параметров Holt (alpha/beta) через разбиение на обучение и валидацию.
    // α (alpha) определяет, насколько сильно новое наблюдение влияет на уровень;
    // β (beta) определяет, насколько быстро изменяется тренд.
    private ForecastEvaluation evaluateHoltParameters(List<DemandPoint> history, int validationWindow) {

        if (history.size() < MIN_HISTORY_POINTS) {
            return new ForecastEvaluation(ALPHA_GRID[0], BETA_GRID[0], Optional.empty());
        }
        // Делим историю на две части: train — на этом “обучаем”, validation — на этом проверяем качество
        int validationSize = Math.min(validationWindow, Math.max(1, history.size() / 4));
        int trainingSize = history.size() - validationSize;
        if (trainingSize < 2) {
            return new ForecastEvaluation(ALPHA_GRID[0], BETA_GRID[0], Optional.empty());
        }

        // В коде используется перебор по сетке (grid): есть набор значений α ( 0.1, 0.2, …), есть набор значений β
        ForecastEvaluation best = null;
        for (double alpha : ALPHA_GRID) {
            for (double beta : BETA_GRID) {
                // Для каждой пары (α,β): строится прогноз на validation-часть
                ForecastMetrics metrics = validateHoltCandidate(history, trainingSize, validationSize, alpha, beta);
                if (metrics == null) {
                    continue;
                }
                // Cчитается ошибка (MAE/MAPE),
                // MAE - Показывает среднюю ошибку в единицах товара.
                // MAPE - Показывает среднюю ошибку в процентах, что удобно для сравнения товаров с разным объёмом спроса.
                // Выбирается пара с минимальной ошибкой
                if (best == null || metrics.mae() < best.metrics().orElseThrow().mae()) {
                    best = new ForecastEvaluation(alpha, beta, Optional.of(metrics));
                }
            }
        }
        if (best == null) {
            return new ForecastEvaluation(ALPHA_GRID[0], BETA_GRID[0], Optional.empty());
        }
        return best;
    }

    // Проверка одного набора Holt: строим прогноз на валидацию и считаем ошибки.
    // Сравниваем прогноз и фактические значения на отложенном участке.
    private ForecastMetrics validateHoltCandidate(List<DemandPoint> history,
                                                  int trainingSize,
                                                  int validationSize,
                                                  double alpha,
                                                  double beta) {
        List<Double> trainingValues = history.subList(0, trainingSize).stream()
                .map(point -> (double) point.quantity())
                .toList();
        if (trainingValues.size() < 2) {
            return null;
        }
        // Выполняем сглаживание Holt по истории и возвращаем финальные уровень и тренд. →
        HoltState state = runHolt(trainingValues, alpha, beta);
        List<Double> predictions = new ArrayList<>();
        //forecast(k) = level + k × trend. Это линейное продолжение текущей тенденции спроса
        for (int i = 1; i <= validationSize; i++) {
            predictions.add(state.level() + i * state.trend());
        }
        List<Double> actuals = history.subList(trainingSize, trainingSize + validationSize).stream()
                .map(point -> (double) point.quantity())
                .toList();
        return calculateMetrics(predictions, actuals);
    }

    // Построение прогноза Holt: берем финальные уровень и тренд и выдаем точки на горизонт.
    // На каждом шаге значение = уровень + шаг * тренд (без отрицательных значений).
    private List<ForecastPoint> generateHoltForecast(List<DemandPoint> history,
                                                     int horizon,
                                                     double alpha,
                                                     double beta,
                                                     ForecastGranularity granularity) {
        List<Double> values = history.stream()
                .map(point -> (double) point.quantity())
                .toList();
        //Снова запускаем Holt — уже на ВСЕЙ истории →
        HoltState state = runHolt(values, alpha, beta);
        List<ForecastPoint> points = new ArrayList<>(horizon);
        LocalDate startDate = history.get(history.size() - 1).date();
        // Строим точки прогноза
        for (int step = 1; step <= horizon; step++) {
            double prediction = Math.max(0, state.level() + step * state.trend());
            //Формируем даты
            points.add(new ForecastPoint(advanceDate(startDate, step, granularity), prediction));
        }
        return points;
    }

    // Выполняем сглаживание Holt по истории и возвращаем финальные уровень и тренд.
    // Уровень — сколько товара в среднем покупают в текущий момент времени
    // Тренд — растёт спрос или падает и на сколько за период.
    private HoltState runHolt(List<Double> values, double alpha, double beta) {
        //level = y[0]. Первое наблюдение временного ряда
        double level = values.get(0);
        //trend = y[1] - y[0]. Разность между первыми значениями
        double trend = values.size() > 1 ? values.get(1) - values.get(0) : 0;
        double prevLevel;
        for (int i = 1; i < values.size(); i++) {
            double observation = values.get(i);
            prevLevel = level;
            level = alpha * observation + (1 - alpha) * (level + trend);
            trend = beta * (level - prevLevel) + (1 - beta) * trend;
        }
        return new HoltState(level, trend);
    }

    // Подсчет метрик качества (MAE и MAPE) по спискам прогнозов и фактических значений.
    // MAE — средняя абсолютная ошибка, MAPE — средняя процентная ошибка.
    private ForecastMetrics calculateMetrics(List<Double> predictions, List<Double> actuals) {
        DoubleSummaryStatistics maeStats = new DoubleSummaryStatistics();
        double mapeSum = 0;
        int mapeCount = 0;
        for (int i = 0; i < actuals.size(); i++) {
            double actual = actuals.get(i);
            double forecast = predictions.get(i);
            double error = Math.abs(actual - forecast);
            maeStats.accept(error);
            if (actual != 0) {
                mapeSum += error / Math.abs(actual);
                mapeCount++;
            }
        }
        double mae = maeStats.getCount() == 0 ? 0 : maeStats.getAverage();
        double mape = mapeCount == 0 ? 0 : (mapeSum / mapeCount) * 100;
        return new ForecastMetrics(mae, mape, (int) maeStats.getCount());
    }

    // Агрегируем дневной спрос в недельный, выравнивая недели по понедельнику.
    private DemandSeries aggregateWeeklySeries(DemandSeries dailySeries, int historyWeeks) {
        Map<LocalDate, Long> weeklyTotals = new HashMap<>();
        for (DemandPoint point : dailySeries.points()) {
            LocalDate weekStart = point.date().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            weeklyTotals.merge(weekStart, point.quantity(), Long::sum);
        }
        LocalDate endWeek = dailySeries.endDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate startWeek = endWeek.minusWeeks(Math.max(historyWeeks - 1, 0));
        List<DemandPoint> weeklyPoints = new ArrayList<>(historyWeeks);
        LocalDate pointer = startWeek;
        for (int i = 0; i < historyWeeks; i++) {
            long quantity = weeklyTotals.getOrDefault(pointer, 0L);
            weeklyPoints.add(new DemandPoint(pointer, quantity));
            pointer = pointer.plusWeeks(1);
        }
        return new DemandSeries(
                dailySeries.productId(),
                startWeek,
                endWeek,
                List.copyOf(weeklyPoints),
                dailySeries.insufficientData()
        );
    }

    // Сдвигаем дату вперед на шаг в зависимости от выбранной детализации.
    private LocalDate advanceDate(LocalDate startDate, int step, ForecastGranularity granularity) {
        if (granularity == ForecastGranularity.WEEK) {
            return startDate.plusWeeks(step);
        }
        return startDate.plusDays(step);
    }

    private record HoltState(double level, double trend) {
    }

    private record ForecastEvaluation(double alpha, double beta, Optional<ForecastMetrics> metrics) {
    }
}
