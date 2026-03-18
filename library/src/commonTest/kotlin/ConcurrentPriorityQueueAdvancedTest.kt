/*
 * Copyright 2026 Kaloyan Karaivanov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalCoroutinesApi::class)

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

class ConcurrentPriorityQueueAdvancedTest {

    private data class Task(val id: String, val priority: Int)

    /**
     * ТЕСТ 1: Доказва критичния бъг с `binarySearch` при еднакви приоритети.
     * Ако два елемента имат еднакъв приоритет, ъпдейтът на единия може да изтрие другия.
     */
    @Test
    fun `test state corruption on equal priority updates`() = runTest {
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = 5,
            priorityComparator = compareByDescending { it.priority },
            uniqueKeySelector = { it.id },
        )

        // Добавяме два различни елемента с ЕДНАКЪВ приоритет
        queue.add(Task("A", 10))
        queue.add(Task("B", 10))

        // Сега ъпдейтваме приоритета на "B", така че да стане по-висок
        queue.add(Task("B", 20))

        val items = queue.items.value

        // Очакваме и "A", и "B" да са в списъка.
        // Ако има бъг, "A" ще е изчезнал, защото binarySearch е намерил и изтрил него вместо старото "B".
        val ids = items.map { it.id }
        assertTrue(ids.contains("A"), "КРИТИЧЕН БЪГ: Елемент 'A' беше погрешно изтрит!")
        assertTrue(ids.contains("B"), "Елемент 'B' липсва!")
        assertEquals(2, items.size)
    }

    /**
     * ТЕСТ 2: Стрес тест за памет и време при голям капацитет.
     * Измерва колко време отнема O(N) мутацията вътре в CAS цикъла.
     */
    @Test
    fun `test performance and memory footprint with large capacity`() = runTest {
        val largeCapacity = 10_000
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = largeCapacity,
            priorityComparator = compareBy { it.priority },
            uniqueKeySelector = { it.id }
        )

        val concurrencyLevel = 10000
        val insertsPerThread = 10000

        // Загряване (Warm-up) на JVM за по-точни метрики
        val warmupQueue = ConcurrentPriorityQueue<Task, String>(100, compareBy { it.priority }) { it.id }
        warmupQueue.add(Task("W", 1))

        val executionTime = measureTime {
            val jobs = (1..concurrencyLevel).map { threadId ->
                launch(Dispatchers.Default) {
                    for (i in 1..insertsPerThread) {
                        // Симулираме случайни приоритети и ключове, за да форсираме
                        // разместване в средата на масива (O(N) изместване)
                        val priority = (1..100_000).random()
                        queue.add(Task("T-$threadId-$i", priority))
                    }
                }
            }
            jobs.joinAll()
        }

        val resultSize = queue.items.value.size
        println("Изпълнени ${concurrencyLevel * insertsPerThread} конкурентни операции.")
        println("Време за изпълнение: $executionTime")
        println("Крайни елементи в опашката: $resultSize")

        // Валидация
        assertTrue(resultSize <= largeCapacity)
        val isSorted = queue.items.value.zipWithNext { a, b -> a.priority <= b.priority }.all { it }
        assertTrue(isSorted, "Опашката не е правилно сортирана след масовите операции!")

        // Проверка за консистентност (Map vs List)
        // Тъй като Map не е публичен, проверяваме индиректно:
        // Ако опитаме да добавим същите елементи с по-слаб приоритет, размерът не трябва да се променя.
        val snapshot = queue.items.value
        snapshot.take(10).forEach { queue.add(it.copy(priority = it.priority + 1000)) }
        assertEquals(snapshot, queue.items.value, "Десинхронизация между вътрешния Map и List!")
    }

    /**
     * ТЕСТ 3: Добавяне на елементи в напълно обратен ред (Worst-case scenario за вмъкване).
     */
    @Test
    fun `test worst case sequential insertions`() = runTest {
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = 100,
            priorityComparator = compareBy { it.priority }, // Най-малкото е с най-висок приоритет
            uniqueKeySelector = { it.id }
        )

        // Добавяме ги от най-малкия към най-големия (вмъкване винаги в края - най-бързо)
        val timeFast = measureTime {
            for (i in 1..1000) queue.add(Task("F$i", i))
        }

        val queueReversed = ConcurrentPriorityQueue<Task, String>(
            maxSize = 100,
            priorityComparator = compareBy { it.priority },
            uniqueKeySelector = { it.id }
        )

        // Добавяме ги от най-големия към най-малкия (вмъкване винаги на индекс 0 - изисква O(N) shift всеки път)
        val timeSlow = measureTime {
            for (i in 1000 downTo 1) queueReversed.add(Task("S$i", i))
        }

        println("Време за Best-case вмъкване: $timeFast")
        println("Време за Worst-case вмъкване: $timeSlow")
        assertEquals(100, queueReversed.items.value.size)
        assertEquals(1, queueReversed.items.value.first().priority)
    }

    @Test
    fun `test DETERMINISTIC corruption on equal priority updates`() = runTest {
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = 10,
            priorityComparator = compareBy { it.priority }, // По-малкото число е с по-висок приоритет
            uniqueKeySelector = { it.id }
        )

        // 1. Създаваме 3 задачи с АБСОЛЮТНО еднакъв приоритет
        queue.add(Task("A", 50))
        queue.add(Task("B", 50))
        queue.add(Task("C", 50))

        // Към този момент вътрешният списък е: [C(50), B(50), A(50)]
        // Защото при равен приоритет binarySearch връща индекс 0 и отмества останалите.

        // 2. Обновяваме "A" с по-лош приоритет (напр. 100)
        // Логиката би трябвало да премахне A(50) и да добави A(100).
        queue.add(Task("A", 100))

        val items = queue.items.value
        val idsInList = items.map { it.id }

        println("Текущо състояние на списъка след ъпдейта: $items")

        // 3. Валидация - тук тестът ЩЕ ГРЪМНЕ
        assertTrue(
            idsInList.contains("B"),
            "КРИТИЧЕН БЪГ: Елементът 'B' беше погрешно изтрит вместо 'A', защото binarySearch се обърка!"
        )

        assertEquals(
            1,
            idsInList.count { it == "A" },
            "КРИТИЧЕН БЪГ: Елементът 'A' се среща повече от веднъж в списъка! (Старото A(50) не е изтрито)"
        )

        assertEquals(3, items.size, "Размерът на списъка не е правилен!")
    }

    @Test
    fun `test DETERMINISTIC corruption on BETTER priority update`() = runTest {
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = 10,
            priorityComparator = compareBy { it.priority },
            uniqueKeySelector = { it.id }
        )

        // 1. Добавяме 3 задачи с еднакъв приоритет
        queue.add(Task("A", 50))
        queue.add(Task("B", 50))
        queue.add(Task("C", 50))

        // 2. Обновяваме "A" с ПО-ДОБЪР приоритет (напр. 10).
        // Това прескача бързата проверка и форсира премахването на стария елемент.
        queue.add(Task("A", 10))

        val items = queue.items.value
        val idsInList = items.map { it.id }

        println("Текущо състояние: $items")

        // 3. Валидация
        assertTrue(
            idsInList.contains("B"),
            "КРИТИЧЕН БЪГ: 'B' беше изтрито вместо 'A', защото binarySearch намери неговия индекс!"
        )
        assertTrue(
            idsInList.contains("C"),
            "КРИТИЧЕН БЪГ: 'C' липсва!"
        )
        assertEquals(
            1,
            idsInList.count { it == "A" },
            "КРИТИЧЕН БЪГ: 'A' се дублира в списъка (старото A(50) не е изтрито)!"
        )
    }

    @Test
    fun `test memory leak and desynchronization when exceeding maxSize`() = runTest {
        // Инициализираме опашка с много малък капацитет (maxSize = 2)
        val queue = ConcurrentPriorityQueue<Task, String>(
            maxSize = 2,
            priorityComparator = compareBy { it.priority }, // По-малкото число е по-добър приоритет
            uniqueKeySelector = { it.id }
        )

        // 1. Запълваме опашката докрай
        queue.add(Task("A", 10))
        queue.add(Task("B", 20))

        // Към този момент: List = [A(10), B(20)], Map = {A, B}

        // 2. Добавяме нов, по-добър елемент, който ще избута най-слабия ("B") от списъка
        queue.add(Task("C", 5))

        // Взимаме директен достъп до вътрешното състояние
        val state = queue.items.value
        val list = state
        val map = queue.persistentMap

        println("Състояние на списъка: $list")
        println("Състояние на Map-а: $map")

        // 3. Проверяваме списъка (Това ще мине успешно)
        assertEquals(2, list.size, "Списъкът трябва да съдържа точно 2 елемента.")
        assertTrue(list.none { it.id == "B" }, "Елемент 'B' трябва да е изтрит от списъка.")
        assertEquals("C", list[0].id)
        assertEquals("A", list[1].id)

        // 4. Проверяваме Map-а - ТУК ТЕСТЪТ ЩЕ СЕ ПРОВАЛИ заради Memory Leak-а
        assertNull(
            map["B"],
            "КРИТИЧЕН БЪГ (Memory Leak): 'B' е премахнат от списъка, но все още се пази в Map-а!"
        )

        assertEquals(
            2,
            map.size,
            "КРИТИЧЕН БЪГ: Размерът на Map-а (${map.size}) е по-голям от размера на списъка (${list.size})!"
        )
    }
}