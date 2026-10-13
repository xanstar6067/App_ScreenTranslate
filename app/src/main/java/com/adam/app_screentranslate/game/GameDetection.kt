package com.adam.app_screentranslate.game

enum class GameDetectionStatus(val description: String) {
    FOUND("Игра определена · профиль применяется"),
    PROFILE_DISABLED("Игра определена · профиль выключен"),
    DISABLED("Определение игры выключено"),
    NO_ACCESS("Нет доступа к статистике использования"),
    UNAVAILABLE("Статистика использования недоступна"),
    NO_EVENTS("Нет событий для определения приложения"),
    BACKGROUND("Нет приложения с подтверждённым активным состоянием"),
    MULTIPLE_APPS("Несколько активных приложений · игра не выбрана"),
    IGNORED("Lenslate, домашний экран или системный интерфейс · без профиля"),
    ERROR("Не удалось определить игру или загрузить профиль")
}

/** Only the latest attempt, in process memory; never includes screen text or credentials. */
data class GameDetection(
    val checkedAt: Long,
    val status: GameDetectionStatus,
    val packageName: String? = null,
    val title: String? = null
)
