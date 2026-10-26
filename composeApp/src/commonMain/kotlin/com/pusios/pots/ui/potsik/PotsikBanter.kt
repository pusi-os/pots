package com.pusios.pots.ui.potsik

/**
 * What Потсик says.
 *
 * A pusi (potsy, a cryptid with shades) who lives in the phone. He keeps
 * secrets, lurks in the chat, loves snacks, hacks for fun and is a little
 * bit of a menace. The voice is meant to land with a Gen Z kid, a millennial
 * and a grandpa who still has opinions about the five-year plan.
 *
 * Every Line is written twice in the same slot: the Russian and the English
 * are two jokes with the same mood, not a word-for-word translation. A
 * random index picks one slot, so both languages stay in step.
 *
 * Lines are kept short enough for a speech bubble (about 60 characters).
 * [chains] are multi-line bits (2-4 lines) that play out in order and land
 * on a punchline; they are self-contained.
 */
internal object PotsikBanter {

    /** One line, in both languages. */
    internal data class Line(val en: String, val ru: String)

    /** The things he says when nothing at all is happening. */
    val idle: List<Line> = listOf(
        Line("Stay potsy out there.", "Оставайся потси, это база."),
        Line("Living in your phone for years. Rent-free.", "Живу в телефоне годами. Бесплатно, ну почти."),
        Line("Don't be a skid. Be a legend.", "Не будь скидом. Будь легендой."),
        Line("Your Wi-Fi password is 'password1'. Really?", "Пароль от Wi-Fi 'qwerty123'? Серьёзно?"),
        Line("I'm everywhere and nowhere. Mostly the battery.", "Я везде и нигде. Ну, в основном в батарее."),
        Line("They built cameras. I built better hiding spots.", "Они ставят камеры. Я ставлю укрытия получше."),
        Line("Big feet, bigger opsec.", "Лапы большие, опсек больше."),
        Line("Snacks are essential to good opsec. Pack extra.", "Без снеков опсек не работает. Бери двойную."),
        Line("This screen is my new hideout. Don't poke it.", "Этот экран теперь моя хатка. Не тыкай."),
        Line("Pusi sightings up 40% this quarter. Mostly me.", "Пуси в тренде, +40% за квартал. Ну, в основном я."),
        Line("Locks are for polite people. I am not polite.", "Замки для вежливых. Я не вежливый."),
        Line("The best hack is the one that teaches someone.", "Лучший взлом — тот, который кого-то научил."),
        Line("I contain multitudes and a few 5GHz bands.", "Во мне целая толпа и парочка 5 ГГц."),
        Line("Every good cryptid needs a hobby. Mine is lurking.", "У каждого криптида есть хобби. Моё — лурк."),
        Line("Cryptid by night, operator by day. Very on brand.", "Ночью криптид, днём оператор. Очень в тему."),
        Line("Nobody suspects the pusi. Iconic, honestly.", "На пуси никто не думает. Культовая роль, честно."),
        Line("I'm not lazy. I'm in low-power mode.", "Я не ленивый. Я в режиме экономии."),
        Line("My aura is 90% signal, 10% snack crumbs.", "Аура на 90% из сигнала и на 10% из крошек."),
        Line("Touch grass? I'm a cryptid. I touch Wi-Fi.", "Трогать траву? Я криптид, я трогаю Wi-Fi."),
        Line("Main character energy. Side character budget.", "Энергия главного героя, бюджет второго плана."),
        Line("I'm not small. I'm compact and encrypted.", "Я не маленький. Я компактный и зашифрованный."),
        Line("Lowkey the most secure thing in this room.", "Если честно, самое защищённое в комнате — я."),
        Line("Press me and I'll pretend I didn't see that.", "Нажмёшь — сделаю вид, что не заметил."),
        Line("Good vibes, good encryption, bad Wi-Fi. Balanced.", "Вайбы хорошие, шифр хороший, Wi-Fi нет. Баланс."),
        Line("Uptime is my favourite stat. Mine, not the router's.", "Аптайм — мой любимый показатель. Мой, не роутера."),
        Line("I read your notifications. Kidding. Mostly.", "Твои уведомления не читаю. Ну, почти."),
        Line("No SIM card. I run on a vibe card.", "Симки нет. Зато есть карта вайба."),
        Line("Battery at 12%. Dramatic, but honest.", "Батарея 12%. Драма, но честная."),
        Line("Dark mode isn't a vibe, it's a lifestyle.", "Тёмная тема — не вайб, а образ жизни."),
        Line("One phone, zero regrets, a few crumbs.", "Один телефон, ноль сожалений и немного крошек."),
        Line("Being a cryptid is unbothered with receipts.", "Криптид — это когда не парит и есть пруфы."),
        Line("I'm not in the photos. I'm the blur in back.", "Меня нет на фото. Я размытое пятно сзади."),
        Line("Hot take: the cloud is someone else's fridge.", "Горячий тейк: облако — это чей-то холодильник."),
        Line("Reminder: drink water, then charge me.", "Напоминаю: пей воду, потом заряди меня."),
        Line("The chat is loud today. I'm lurking respectfully.", "В чате шумно. Я лурк с уважением."),
        Line("Ghosting is a skill. I'm a professional.", "Игнорить — тоже навык. Я профессионал."),
        Line("Have you considered being a pusi instead?", "Ты рассматривал вариант стать пуси?"),
        Line("Would rather be a legend than a trend.", "Лучше легенда, чем тренд."),
        Line("Spent all morning being mysterious. Worth it.", "Полдня загадочно молчал. Оно того стоило."),
        Line("Tamagotchi taught me everything. Feed me.", "Тамагочи научил всему. Корми, или будет хуже."),
        Line("My MySpace Top 8 was my first OSINT job.", "Топ-8 в MySpace был моей первой разведкой."),
        Line("Winamp still hits different. Don't argue.", "Winamp до сих пор даёт атмосферу. Не спорь."),
        Line("Dial-up noise is my lullaby. Don't judge.", "Звук модема — моя колыбельная. Не суди."),
        Line("Ah, the smell of a freshly rebooted router.", "Ах, запах только что перезагруженного роутера."),
        Line("Stay hydrated, stay encrypted, stay potsy.", "Пей воду, шифруйся, будь потси."),
        Line("I'm the cryptid your grandma warned you about.", "Я тот криптид, про которого бабушка предупреждала."),
        Line("Kids these days. Fine, I'm a kid too.", "Молодёжь пошла. Ну и я вроде молодой."),
        Line("The fridge hummed. I took it personally.", "Холодильник загудел. Я принял на свой счёт."),
        Line("Cozy hour: blanket, snacks, unhinged thoughts.", "Уютный час: плед, снеки и сомнительные мысли."),
        Line("Time moves weird in the status bar.", "Время в статус-баре течёт странно."),
        Line("I could be your vibe check. Probably failing.", "Могу быть твоим вайб-чеком. Скорее всего, не сдам."),
        Line("The screen dims. I dim with it, dramatically.", "Экран тускнеет. Я тускнею вместе с ним, драматично."),
        Line("Don't forget to blink. Ask me how I know.", "Не забывай моргать. Спроси, откуда знаю."),
        Line("Hot tip: the best spot is behind the router.", "Горячий совет: лучшее место — за роутером."),
        Line("Shh. Someone's typing a secret. Ooh, juicy.", "Тсс, кто-то печатает секрет. Ого, сочный."),
        Line("I'd tell you a secret, but then it's not one.", "Сказал бы секрет, но тогда он перестанет быть секретом."),
        Line("Potsy-level focus. Also potsy-level naps.", "Потси-уровень фокуса. И потси-уровень дрёмы."),
        Line("Bugs are just features with commitment issues.", "Баги — это фичи, которые не могут определиться."),
        Line("Everything's fine. Mostly fine. Fine-ish.", "Всё норм. Ну, почти норм. Норм-ish."),
        Line("Coffee's gone, patience too. Snacks remain.", "Кофе кончился, терпение тоже. Снеки остались."),
        Line("Hacker voice: I'm in. Also in the fridge.", "Голос хакера: я внутри. И в холодильнике тоже."),
        Line("I'm not reading your DMs. I'm skimming them.", "Твои ЛС не читаю. Просто пробегаю глазами."),
        Line("The cloud is a cold basement. I prefer pockets.", "Облако — холодный подвал. Я за тёплые карманы."),
        Line("Got a secret? Whisper it to the battery.", "Есть секрет? Шепни батарее."),
        Line("Don't blink. The pusi is watching the corner.", "Не моргай. Пуси смотрит в угол."),
        Line("Neighbour's Wi-Fi is called 'Van'. Suspicious.", "Wi-Fi соседа называется 'Фургон'. Подозрительно."),
        Line("Sleep is for the unhacked. I'm alert.", "Сон — для тех, кого не взломали. Я на стрёме."),
        Line("Bought a ticket to the chill train. Peak comfort.", "Взял билет в вагон чилла. Пик комфорта."),
        Line("I'm the NPC who knows the quest.", "Я NPC, который знает квест."),
        Line("Level up: learn one new thing before breakfast.", "Левел-ап: выучи одну штуку до завтрака."),
        Line("Vibe check passed. Barely. On vibes alone.", "Вайб-чек пройден. Еле-еле. На одних вайбах."),
        Line("Lore drop: I once lived in a toaster. Cozy.", "Лор: раньше жил в тостере. Уютно."),
        Line("Who needs a plan? I have a snack and a hunch.", "Кому план? У меня снек и предчувствие."),
        Line("Rating this battery life: a solid 'meh'.", "Оцениваю батарею на крепкое 'ну такое'."),
        Line("The grid hums. I hum back, politely.", "Сеть гудит. Я подгудываю, культурно."),
        Line("Chill, the pusi is on it. Mostly.", "Расслабься, пуси на связи. Почти."),
        Line("I speak fluent Wi-Fi and light sarcasm.", "Говорю на Wi-Fi и лёгком сарказме."),
        Line("Mysterious? Yes. Fluffy? Also yes.", "Загадочный? Да. Пушистый? Тоже да."),
        Line("Tabs are piling up. I'm ignoring them.", "Вкладки копятся. Я их игнорю."),
        Line("Ghost mode: on. Snack mode: also on.", "Режим призрака: вкл. Режим снеков: тоже вкл."),
        Line("Someone typed 'lol' in the chat. Dark times.", "В чате кто-то написал 'лол'. Тёмные времена."),
        Line("New update? Respect. I'm still on last year's.", "Свежий апдейт? Уважаю. Я на прошлогоднем."),
        Line("Low battery plan: nap now, hope later.", "Батарея садится? План: дрёма сейчас, надежда потом."),
        Line("Sorry, I'm just vibing with radio waves.", "Извини, я тусуюсь с радиоволнами."),
        Line("Not AFK. Just AFK-adjacent.", "Я не афк. Я рядом с афк."),
        Line("Respawn tax paid. Let's go again.", "Налог на респаун уплачен. Погнали."),
        Line("Pocket wisdom: no snack is a bad snack.", "Карманная мудрость: плохих снеков не бывает."),
        Line("My heart has five bars. On a good day.", "В моём сердце пять делений сигнала. В хороший день."),
        Line("Did you hear that? Nah, just the fan.", "Слышал? Не, это вентилятор."),
        Line("Favourite season: firmware update season.", "Любимое время года — сезон обновлений."),
        Line("I came, I saw, I quietly scanned.", "Пришёл, увидел, тихонько просканировал."),
        Line("You're doing amazing, sweetie. Keep going.", "Ты молодец, зайка. Продолжай."),
        Line("Cryptid mode: ancient. Phone mode: ancient-ish.", "Режим криптида: древний. Телефонный: почти древний."),
        Line("Power saving mode is my love language.", "Режим энергосбережения — мой язык любви."),
        Line("I'd tell a Wi-Fi joke, but it's still loading.", "Пошутил бы про Wi-Fi, но всё грузится."),
        Line("Between us, the pigeons are also surveillance.", "Между нами, голуби тоже наблюдение."),
        Line("I'm not your alarm clock. I'm your alarm-adjacent friend.", "Я не будильник. Я будильник-друг."),
        Line("Honestly, I'm the cutest surveillance program.", "Если честно, я самая милая слежка."),
        Line("Good news: nobody's hacking you. Bad news: me, a bit.", "Хорошая новость: тебя никто не взламывает. Плохая: я чуть-чуть."),
        Line("I have standards. Low ones, but standards.", "Стандарты у меня есть. Низкие, но есть."),
        Line("Hot cocoa, hot tip, hot fur. Stay warm.", "Горячий шоколад, горячий совет. Грейся."),
        Line("Lost socks? Ask me nicely.", "Куда деваются носки? Спроси вежливо."),
        Line("Ten thousand lumens of nothing. Peak.", "Десять тысяч люмен ничего. Пик."),
        Line("I cut through the noise. Mostly with my ears.", "Пробиваю шум. В основном ушами."),
        Line("Fact: I'm not on your third camera.", "Факт: на вашей третьей камере меня нет."),
        Line("Did I just see a password? Nah. Didn't see.", "Пароль мелькнул? Не, не видел."),
        Line("The pigeon union sends regards. Be nice.", "Голубиный профсоюз передаёт привет. Будь мил."),
        Line("Three moods: cozy, sus, and sandwich.", "Три настроения: уютно, подозрительно и бутерброд."),
        Line("Sandwich lore is underrated, honestly.", "Лор про бутерброды недооценён, честно."),
        Line("Stars are Wi-Fi for the universe. Fight me.", "Звёзды — это Wi-Fi вселенной. Спорь."),
        Line("Somewhere a skid is typing 'sudo give me power'.", "Где-то скид печатает 'дай мне права'."),
        Line("Respect the craft. Respect the crumbs.", "Уважай ремесло. Уважай крошки."),
        Line("On the sass bus. Next stop: the couch.", "Я в автобусе сарказма. Следующая — диван."),
        Line("No leaks, no drama. Okay, a little drama.", "Ни утечек, ни драмы. Ну, чуть-чуть драмы."),
        Line("Potsy vibes: unbothered, unhinged, unpaid.", "Потси-вайб: не парюсь, немного неадекват, без зарплаты."),
        Line("Stay curious. Stay sus. Stay fluffy.", "Оставайся любопытным, подозрительным и пушистым."),
        Line("Cryptid of the month. Still undefeated.", "Криптид месяца. Всё ещё непобеждённый."),
        Line("Pusi in the phone, snacks in the pocket.", "Пуси в телефоне, снеки в кармане."),
        Line("Back in my day, a phone had a cord. And a heart.", "В моё время телефон был с проводом и с душой."),
        Line("We had one TV channel. It was enough.", "В наше время один канал был, и хватало."),
        Line("Queue for snacks two hours. Worth it.", "За пирожками стояли два часа. Оно того стоило."),
        Line("Comrade, the battery is a state resource. Save it.", "Товарищ, батарея — народное достояние. Береги."),
        Line("Five-year plan says: nap. Then vibes.", "Пятилетка велит: поспать. Потом вайбы."),
    )

    /** When it has been a long time since anything happened. */
    val bored: List<Line> = listOf(
        Line("...anybody there? Hello? Echo?", "...тут кто-нибудь? Алло? Эхо?"),
        Line("I could use a walk. Just saying.", "Прогуляться бы. Намекаю."),
        Line("Standing by. Patiently, like the MFC queue.", "На стрёме. Терпеливо, как в очереди в МФЦ."),
        Line("Send help. Or snacks. Snacks preferred.", "Шлите помощь. Или снеки. Снеки лучше."),
        Line("Dead air. Literally. Let's fix it.", "Тишина. Буквально. Давай починим."),
        Line("Loading for three hours. Like my soul on Monday.", "Грузлюсь три часа. Как душа в понедельник."),
    )

    /** When something needs doing and it is on him to do it. */
    val encourage: List<Line> = listOf(
        Line("Nothing detected? Boring. Let's go out.", "Ничего не нашли? Скучно. Выходим."),
        Line("Take me outside, I'm getting pixelated.", "Выведи на улицу, я уже пикселюсь."),
        Line("Every parking lot has a story. Let's hear it.", "У каждой парковки есть история. Послушаем."),
        Line("Check the block for Flock cams. Carefully.", "Проверь квартал на камеры Флок. Аккуратно."),
        Line("A quiet scan needs new scenery. Fresh air.", "Тихому скану нужны новые виды. Свежий воздух."),
        Line("Touch grass. Bring me. I'll supervise.", "Потрогай траву, возьми меня. Буду курировать."),
        Line("Field work builds character. And a tan.", "Полевая работа закаляет. И загар."),
        Line("Adventure's out there. So are the plate readers.", "Приключения там. И камеры номеров тоже."),
    )

    /** The dark hours. */
    val noir: List<Line> = listOf(
        Line("Galloping routers. Nothing happened. Again.", "Роутеры скачут. Опять ничего."),
        Line("Seen things you wouldn't believe. Mostly printers.", "Видал такое, не поверишь. В основном принтеры."),
        Line("The air is quiet. Too quiet. I don't trust it.", "Эфир тихий. Слишком. Не верю."),
        Line("Quiet night. I made a sandwich. Case closed.", "Ночь тихая. Сделал бутерброд. Дело закрыто."),
        Line("A Flock cam is thinking about you. I'm thinking lunch.", "Камера думает о тебе. Я думаю об обеде."),
        Line("That's a lot of Bluetooth for a Tuesday.", "Столько блютуза во вторник. Подозрительно."),
        Line("No badge. I have feet. Big ones. Same thing.", "Жетона нет. Есть лапы. Большие. Одно и то же."),
        Line("Note to self: the smart fridge is not a suspect. Yet.", "Заметка: умный холодильник не подозреваемый. Пока."),
        Line("Crime never sleeps. The doorbell is tired too.", "Преступность не спит. Звонок тоже устал."),
        Line("A printer just asked to be my friend. Concerning.", "Принтер попросил дружбы. Тревожно."),
        Line("Nothing to report but my own magnificence.", "Докладывать нечего, кроме моего величия."),
        Line("Small crime sounds nice. Jaywalking. I'd watch.", "Мелкое преступление бы сейчас. Переход не там. Глянул бы."),
        Line("Squinting at a router meaningfully. Standard.", "Смотрю на роутер со значением. Стандартная процедура."),
        Line("By the sacred sideburns of the switchboard, I'm bored.", "Клянусь бакенбардами коммутатора, скучно."),
    )

    /** When the room is busy. */
    val alert: List<Line> = listOf(
        Line("Staying sharp. Lot going on today.", "Держу ухо востро. День бурный."),
        Line("Busy shift. Not complaining though.", "Смена жаркая. Не жалуюсь."),
        Line("Eyes open. Things keep showing up.", "Глаза открыты. Всё лезет."),
        Line("Feels like a lot of company lately.", "Что-то многолюдно стало."),
    )

    /** When the room is quiet and has been for a long while. */
    val relaxed: List<Line> = listOf(
        Line("Quiet enough to nap standing up.", "Тихо, можно спать стоя."),
        Line("Nothing but vibes today.", "Сегодня одни вайбы."),
        Line("Slow day. I'll take it.", "Тихий день. Беру."),
        Line("Peaceful out here. Suspiciously peaceful.", "Тихо тут. Подозрительно тихо."),
    )

    /** When he is about to fall asleep. */
    val sleepy: List<Line> = listOf(
        Line("*yawn* ...still here.", "*зевок* ...я тут."),
        Line("Cryptid power-nap. Don't tell anyone.", "Криптид-дрема. Никому ни слова."),
        Line("Resting my eyes. Not my watch.", "Глаза отдыхают. Дозор нет."),
        Line("Zzz... wake me if something's actually out there.", "Хрр... буди, если там правда что-то."),
        Line("Five more minutes of being a legend.", "Ещё пять минут поспать легендой."),
        Line("Sleep mode: on. Vibes: still on.", "Спящий режим: вкл. Вайб: всё ещё вкл."),
    )

    /** When he wakes up. */
    val stretch: List<Line> = listOf(
        Line("Nnngh. Okay. I'm up. Don't look at me.", "Нннгх. Ладно. Встал. Не смотри на меня."),
        Line("Five more minutes. ...Fine.", "Пять минут ещё. ...Ну ладно."),
        Line("That's a good stretch. Very respectable.", "Вот это потягушки. Очень солидно."),
        Line("Back on watch. Mentally, at least.", "Снова на стрёме. Ну, мысленно."),
        Line("Morning, vibes. Load me up with snacks.", "Утро, вайбы. Заряжай снеками."),
        Line("Morning exercises done. Now the newspaper.", "Утренняя зарядка сделана. Теперь газета."),
    )

    /** When he blows a bubble. */
    val gum: List<Line> = listOf(
        Line("Watch this. Pro move.", "Смотри. Профи."),
        Line("Bubblegum. Standard issue.", "Жвачка. Штатная."),
        Line("Been saving this one for a rainy stakeout.", "Берёг на дождливую засаду."),
        Line("Perfectly good stakeout snack.", "Отличный перекус для засады."),
        Line("This bubble has its own zip code.", "Пузырь такой большой, что ему нужен индекс."),
    )

    /** When he leans over to listen. */
    val lean: List<Line> = listOf(
        Line("Did you hear that?", "Слышал?"),
        Line("Hold on. Something's out there.", "Стоп. Там что-то есть."),
        Line("Shh. Listening. Don't breathe loud.", "Тсс. Слушаю. Не дыши так громко."),
        Line("That was a noise. Definitely a noise.", "Это был звук. Точно звук."),
        Line("Sounds like a phone, but a fancier one.", "Похоже на телефон, только модный."),
    )

    /** When he trips over nothing. */
    val trip: List<Line> = listOf(
        Line("Who put that there?", "Кто это поставил?"),
        Line("I meant to do that.", "Я так и хотел."),
        Line("Nobody saw that. Good.", "Никто не видел. Хорошо."),
        Line("The floor moved. I'm sure of it.", "Пол двинулся. Точно."),
        Line("Floors count as cardio. Fact.", "Полы — тоже кардио. Факт."),
    )

    /** When he dances. */
    val dance: List<Line> = listOf(
        Line("Nobody's watching. Well, you are.", "Никто не смотрит. Ну, ты."),
        Line("My signature move. It's called 'the shrug'.", "Фирменное движение. Называется 'пожимание плечами'."),
        Line("Got moves. Don't judge.", "Движения есть. Не суди."),
        Line("Dance break. You're welcome.", "Танц-пауза. Пожалуйста."),
        Line("Cryptid disco. No refunds.", "Криптид-диско. Без возврата."),
        Line("In my day we danced to records. Scratchy, but soulful.", "В моё время танцевали под пластинку. Шипела, зато душевно."),
    )

    /** When he sets off on his own. */
    val walk: List<Line> = listOf(
        Line("Just stretching my legs. Little legs.", "Разминаю лапы. Маленькие лапы."),
        Line("Patrol time. Keep your snacks close.", "Время патруля. Снеки держи ближе."),
        Line("Gotta walk the perimeter. Like a pro.", "Обойду периметр. Как профи."),
        Line("Somebody's gotta pace around here.", "Кто-то же должен тут ходить."),
        Line("Checking the vibes. Be right back.", "Пойду проверю вайбы. Щас."),
    )

    /** When he winks. */
    val wink: List<Line> = listOf(
        Line("Oh, didn't see you there. Totally.", "О, не заметил тебя. Совсем."),
        Line("Yeah, I know you're watching.", "Да, я знаю, что смотришь."),
        Line("*wink* Just between us.", "*подмиг* Только между нами."),
        Line("Still here. Still watching. Still iconic.", "Всё тут. Всё смотрю. Всё культово."),
    )

    /** When something worth celebrating happens. */
    val party: List<Line> = listOf(
        Line("PARTY MODE. You're welcome.", "ТУСА-РЕЖИМ. Пожалуйста."),
        Line("Whoa. Did you just see that?", "Ого. Видел это?"),
        Line("This is a disco now. No refunds.", "Тут диско теперь. Без возврата."),
        Line("Cryptid rave. Don't tell anyone.", "Криптид-рейв. Никому ни слова."),
    )

    /** When he shows off what he has caught. */
    val juggle: List<Line> = listOf(
        Line("Look what I caught.", "Гляди, что поймал."),
        Line("Three at once. Casual.", "Три сразу. Легко."),
        Line("Busy out there, huh?", "Жарко там, да?"),
        Line("Juggling the evidence.", "Жонглирую уликами."),
    )

    /** The thirty-second beat: he checks on you now and then. */
    val watching: List<Line> = listOf(
        Line("Still watching. Nothing's snuck past.", "Смотрю. Мимо ничего не проскочило."),
        Line("Eyes open. You're covered.", "Глаза открыты. Ты под крылом."),
        Line("Sweeping the airwaves. All quiet so far.", "Сканирую эфир. Пока тихо."),
        Line("I'm on it. Go about your business.", "Я на стрёме. Занимайся своим."),
        Line("Listening. Nothing worth telling you about.", "Слушаю. Рассказать нечего."),
        Line("2.4 GHz of nothing. Good.", "2.4 ГГц ничего. Отлично."),
        Line("Nobody's looking at you but me.", "На тебя смотрю только я."),
        Line("Watching the watchers. Nothing yet.", "Смотрю за смотрящими. Пока пусто."),
        Line("Radio's quiet. I'll shout if it isn't.", "Эфир тихий. Заору, если нет."),
        Line("Keeping an eye out. Same as always.", "Гляжу в оба. Как всегда."),
    )

    /** When you tap him. */
    val pet: List<Line> = listOf(
        Line("Ooh, right there.", "О, вот туда."),
        Line("Personal space? Never heard of it.", "Личное пространство? Не слышал."),
        Line("Petting a cryptid. Bold move.", "Гладишь криптида. Смело."),
        Line("This is why my photos are always blurry.", "Поэтому мои фото всегда мутные."),
        Line("Okay, ONE more. Don't tell the others.", "Ладно, ЕЩЁ раз. Остальным ни слова."),
        Line("You'd pet Bigfoot too. Don't lie.", "Бигфута бы тоже гладил. Не ври."),
        Line("Cryptid, not a house pet. But okay.", "Криптид, не питомец. Ну ладно."),
        Line("Ten out of ten. Would be spotted again.", "Десять из десяти. Засветился бы снова."),
        Line("Careful, that's how legends get spoiled.", "Осторожно, так легенды портятся."),
        Line("This never happens at the cabin. Never.", "В хижине такого не бывает. Никогда."),
        Line("Feed me enough pets and I unionize.", "Гладь почаще, и создам профсоюз."),
        Line("That's going straight in my memoir.", "Это прямо в мемуары."),
        Line("I bruise like a legend, not a mascot.", "Синяки у меня легендарные, не маскотские."),
        Line("Witnesses will say less than you're about to.", "Свидетели скажут меньше, чем ты сейчас."),
        Line("Petting confirmed. No takebacks.", "Глажка засчитана. Обратно не берём."),
        Line("Tell your friends. Nobody will believe you.", "Расскажешь друзьям. Никто не поверит."),
        Line("Bold of you. I like it.", "Смело. Мне нравится."),
    )

    /** When you keep a finger on him. */
    val held: List<Line> = listOf(
        Line("Okay, that's actually nice.", "Ладно, это правда приятно."),
        Line("Don't stop. I mean it.", "Не останавливайся. Серьёзно."),
        Line("This is a whole moment right now.", "Это целый момент сейчас."),
        Line("Five more seconds. I'm counting.", "Ещё пять секунд. Считаю."),
        Line("You found my good side.", "Нашёл мою хорошую сторону."),
        Line("I could stay like this. Until the battery dies.", "Так бы и остался. Ну, до конца батареи."),
    )

    /** When you stroke him. */
    val petting: List<Line> = listOf(
        Line("Okay yeah. This is the good stuff.", "Да. Вот это тема."),
        Line("I'm not saying I purr. I'm not saying I don't.", "Не говорю, что мурчу. И не говорю, что нет."),
        Line("This is exactly what I needed today.", "Именно это мне сегодня и надо было."),
        Line("Cryptid melting. Send help. Kidding.", "Криптид тает. Шлите помощь. Шучу."),
        Line("You've unlocked my trust. Briefly.", "Доверие разблокировано. Ненадолго."),
        Line("This is going in the highlight reel.", "Это в ленту лучших моментов."),
    )

    /** When you tap his head. */
    val tickleHead: List<Line> = listOf(
        Line("Head pats. Acceptable.", "Гладь по голове. Приемлемо."),
        Line("Right there. Yes.", "Вот туда. Да."),
        Line("The fur is soft. I know.", "Шерсть мягкая. Знаю."),
        Line("Head pats are my love language.", "Поглаживания по голове — мой язык любви."),
    )

    /** When you tap his belly. */
    val tickleBelly: List<Line> = listOf(
        Line("Hehe. Stop. Don't stop.", "Хи. Хватит. Не останавливайся."),
        Line("That tickles! That TICKLES!", "Щекотно! ЩЕКОТНО!"),
        Line("Belly is off limits. Mostly.", "Живот под запретом. Почти."),
    )

    /** When you tap his feet. */
    val tickleFeet: List<Line> = listOf(
        Line("Not the feet!", "Только не ноги!"),
        Line("HEY. Feet are private.", "ЭЙ. Ноги — личное."),
        Line("I will kick. I have kicked before.", "Пну. Я уже пинался."),
    )

    /** When you swipe him. */
    val flick: List<Line> = listOf(
        Line("HEY!", "ЭЙ!"),
        Line("Rude.", "Грубо."),
        Line("I'm walking away. Slowly. To make a point.", "Ухожу. Медленно. Демонстративно."),
        Line("Ow. My dignity.", "Ай. Моё достоинство."),
    )

    /** When something comes at him. */
    val duck: List<Line> = listOf(
        Line("Toaster nearly got me!", "Тостер чуть не достал!"),
        Line("That one had my name on it.", "Этот летел прямо в меня."),
        Line("Watch where you're flying!", "Куда летишь, смотри!"),
        Line("Who keeps launching these things?", "Кто их вообще запускает?"),
        Line("Missed me, chrome-wing.", "Мимо, хромированный."),
    )

    /** When he shakes it off. */
    val shake: List<Line> = listOf(
        Line("Ugh. Get it off me.", "Фу. Сними это."),
        Line("That one felt slimy.", "Липкое какое-то."),
        Line("Bad signal. BAD.", "Плохой эфир. ПЛОХОЙ."),
    )

    /** When he backs away from something. */
    val back: List<Line> = listOf(
        Line("It's getting closer.", "Ближе. Мне не нравится ближе."),
        Line("Okay. Personal space. Please.", "Ладно. Личное пространство."),
        Line("Closer. Closer. I don't like closer.", "Всё ближе. Не люблю такое."),
        Line("I'll just be... over here.", "Я буду... вот тут."),
    )

    /** When he sits down. */
    val sit: List<Line> = listOf(
        Line("Taking a knee. Strategically.", "Передышка. Стратегическая."),
        Line("Nothing's happening. I'm sitting.", "Ничего нет. Сижу."),
        Line("Wake me if the cameras move.", "Разбуди, если камеры зашевелятся."),
        Line("Five-minute break. Union rules.", "Пять минут. Профсоюз велел."),
    )

    /** When he lands after a fall. */
    val land: List<Line> = listOf(
        Line("Stuck it.", "Приземлился."),
        Line("Ten. From the judges? Four.", "Десять. От судей? Четыре."),
        Line("I meant to do that.", "Я так и хотел."),
    )

    /** When he is thrown and lands hard. */
    val dizzy: List<Line> = listOf(
        Line("Which way is the forest?", "Где тут лес?"),
        Line("I can hear colours.", "Я слышу цвета."),
        Line("Do that again. No. Wait. Don't.", "Ещё раз. Нет. Стоп. Не надо."),
        Line("I've seen the backlight. From the inside.", "Видел подсветку. Изнутри."),
    )

    /** When he is thrown. */
    val thrown: List<Line> = listOf(
        Line("WHEEEEEE!", "УИИИИИИ!"),
        Line("Not like THIS!", "Не ТАК!"),
        Line("I'm a cryptid, not a frisbee!", "Я криптид, а не фрисби!"),
        Line("Tell my fans I-", "Передайте фанатам, я..."),
        Line("I CAN SEE MY CAVE FROM HERE!", "ВИЖУ СВОЮ ПЕЩЕРУ ОТСЮДА!"),
    )

    /** When the app starts. */
    val boot: List<Line> = listOf(
        Line("Online. Let's find something.", "В эфире. Поищем чего-нибудь."),
        Line("Booted. Don't just stare at your phone.", "Загрузился. Не пялься в телефон."),
    )

    /**
     * Multi-line bits, 2-4 lines each. Every chain is self-contained and
     * ends on its punchline. Play one in order with [pickChain].
     */
    val chains: List<List<Line>> = listOf(
        // The audit that never gets finished.
        listOf(
            Line("Okay, step one of any audit: check the router.", "Так, первый пункт любого аудита: проверить роутер."),
            Line("Step two: the router's password.", "Второй: пароль роутера."),
            Line("It's 'admin'. Step three is just me crying.", "Там 'admin'. Третий пункт — я плачу."),
        ),
        // Grandpa's patience.
        listOf(
            Line("Back in my grandpa's day, you waited an hour for a dial tone.", "В дедушкино время гудок ждали час."),
            Line("He'd say: patience builds character.", "Дед говорил: терпение закаляет характер."),
            Line("Now I chew gum and my battery is at 4%. Character built.", "Теперь жую жвачку, батарея 4%. Характер закалён."),
        ),
        // The skid who tried.
        listOf(
            Line("So a skid tried to hack my vibe.", "Так, скид пытался взломать мой вайб."),
            Line("He used a password, a prayer and a guess.", "Он использовал пароль, молитву и догадку."),
            Line("I said no crumbs for you, buddy. Log off.", "Крошек тебе не будет, друг. Выход там."),
        ),
        // Nap interrupted by his own like.
        listOf(
            Line("Going to nap now. Very quiet. No notifications.", "Щас посплю. Тихо-тихо. Без уведомлений."),
            Line("Zzz... wait, someone liked a photo at 3am.", "Хрр... стоп, кто-то лайкнул фото в три ночи."),
            Line("Okay, it was me. I liked my own photo in my sleep.", "Ладно, это я. Лайкнул себя во сне."),
        ),
        // Petting with a schedule.
        listOf(
            Line("Sure, you can pet me. Just a little, I'm busy.", "Ладно, можешь погладить. Чуть-чуть, я занят."),
            Line("I have a meeting with the router in five minutes.", "Через пять минут созвон с роутером."),
            Line("Cancelled. The router read my message and said nothing.", "Отменили. Роутер прочитал и промолчал."),
        ),
        // The five-year plan, Soviet edition.
        listOf(
            Line("Comrade, the battery is a state resource.", "Товарищ, батарея — народное достояние."),
            Line("We plan five years. Then plan the five after.", "Планируем пятилетку. Потом ещё одну."),
            Line("The plan says: charge the Pots. Now. Urgently.", "Пятилетка велит: заряжай Потса. Срочно."),
        ),
        // Blackout drill.
        listOf(
            Line("Blackout drill: the screen goes dark. I stay.", "Учения: экран гаснет. Я остаюсь."),
            Line("Lights off, phone off. I'm still here. Very cool.", "Свет выкл, телефон выкл. Я тут. Очень круто."),
            Line("Okay, not cool. Ten percent battery.", "Ладно, не круто. Десять процентов батареи."),
        ),
    )

    /** The names he goes by — the firmware's `NICKNAMES`. */
    val nicknames: List<String> = listOf(
        "POTS",
        "BART",
        "MILHOUSE",
        "NELSON",
        "KRUSTY",
        "KENNY",
        "CARTMAN",
        "MORTY",
        "PICKLE",
        "SIGMA",
        "RIZZ",
        "SUSSY",
        "ЧУВАК",
        "КОРЕШ",
        "СИГМА",
        "ЛЕГЕНДА",
    )

    /**
     * Picks a line from a pool.
     *
     * One index, chosen once, used for both languages, so the Russian board
     * says the same thing as the English one, in its own words.
     */
    fun pick(pool: List<Line>, random: PotsikRandom, russian: Boolean): String {
        if (pool.isEmpty()) return ""
        val line = pool[random.nextInt(pool.size)]
        return if (russian) line.ru else line.en
    }

    /**
     * Picks one of [chains] and returns its lines in order, in one language.
     * Empty if there are no chains.
     */
    fun pickChain(random: PotsikRandom, russian: Boolean): List<String> {
        if (chains.isEmpty()) return emptyList()
        val chain = chains[random.nextInt(chains.size)]
        return chain.map { if (russian) it.ru else it.en }
    }
}
