package de.cantry.csgocasestatsviewerv2.steam.service;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.cantry.csgocasestatsviewerv2.exception.GlobalException;
import de.cantry.csgocasestatsviewerv2.model.InventoryChangeEntry;
import de.cantry.csgocasestatsviewerv2.model.Item;
import de.cantry.csgocasestatsviewerv2.model.Rarity;
import de.cantry.csgocasestatsviewerv2.util.OddsUtils;
import de.cantry.csgocasestatsviewerv2.util.TimeUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static de.cantry.csgocasestatsviewerv2.util.FormatUtils.format;
import static de.cantry.csgocasestatsviewerv2.util.FormatUtils.round;
import static de.cantry.csgocasestatsviewerv2.util.TimeUtils.longToStringDateConverter;
import static java.util.stream.Collectors.toMap;

public class AnalysisService {

    private static AnalysisService instance;

    private File resultFile;

    private OutputStream outputStream;

    public List<InventoryChangeEntry> parseFullHistory(File path) {
        Gson gson = new Gson();
        List<InventoryChangeEntry> inventoryChangeEntries = new ArrayList<>();
        logToConsoleAndFile("Sorted by time", true);

        var files = path.listFiles();

        if (files == null || files.length == 0) {
            throw new GlobalException("No data found. Dump data first");
        }

        for (File f : Objects.requireNonNull(path.listFiles())) {
            //Not pretty but better then nullchecking every field
            try {
                String fileContent = "";
                try {
                    var lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);

                    fileContent = String.join("", lines);
                } catch (Exception e) {
                    StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw);
                    e.printStackTrace(pw);
                    logToConsoleAndFile(sw.toString());
                }
                if (fileContent.isEmpty()) {
                    throw new Exception("Dump with name" + f.getName() + " seems to be corrupted");
                }
                JsonObject obj = gson.fromJson(fileContent, JsonObject.class);
                String currentDumpHTML = obj.get("html").getAsString();
                Document currentDoc = Jsoup.parse(currentDumpHTML);
                HashMap<String, JsonObject> descriptionsByKey = new HashMap<>();
                JsonObject descriptions = obj.get("descriptions").getAsJsonObject();

                for (Map.Entry<String, JsonElement> description : descriptions.get("730").getAsJsonObject().entrySet()) {
                    descriptionsByKey.put(description.getKey(), description.getValue().getAsJsonObject());
                }

                List<Element> rows = currentDoc.getElementsByClass("tradehistoryrow");
                Collections.reverse(rows);
                for (Element tradehistoryrow : rows) {
                    InventoryChangeEntry inventoryChangeEntry = new InventoryChangeEntry();
                    inventoryChangeEntry.setEvent(tradehistoryrow.getElementsByClass("tradehistory_event_description").text());
                    if (tradehistoryrow.getElementsByTag("a").size() > 0) {
                        inventoryChangeEntry.setPartner(tradehistoryrow.getElementsByTag("a").get(0).attr("href"));
                    }
                    String dateString = tradehistoryrow.getElementsByClass("tradehistory_date").get(0).text().replace("am", "AM").replace("pm", "PM").replace("\\t", "").replace("\\r", "").replace("\\n", "");
                    long time = TimeUtils.getTimeFromString(dateString);
                    if (time == 0) {
                        logToConsoleAndFile("Failed to parse date: " + dateString);
                        continue;
                    }
                    inventoryChangeEntry.setTime(time);

                    List<Element> itemGroups = tradehistoryrow.getElementsByClass("tradehistory_items");

                    for (Element items : itemGroups) {
                        if (items.getElementsByClass("tradehistory_items_plusminus").size() == 0) {
                            continue;
                        }
                        String direction = items.getElementsByClass("tradehistory_items_plusminus").get(0).text();
                        Boolean incoming = null;
                        if ("-".equals(direction)) {
                            incoming = false;
                        } else if ("+".equals(direction)) {
                            incoming = true;
                        } else if (itemGroups.size() == 3) {
                            incoming = true;
                        } else {
                            throw new Exception("Failed to parse direction: " + direction);
                        }
                        //items
                        List<Item> parsedItems = new ArrayList<>();
                        for (Element item : items.getElementsByClass("tradehistory_items_group").get(0).children()) {

                            String appid = item.attr("data-appid");
                            String classid = item.attr("data-classid");
                            String instanceid = item.attr("data-instanceid");
                            if (!"730".equals(appid)) {
                                continue;
                            }

                            String descriptionKey = classid + "_" + instanceid;
                            JsonObject description = descriptionsByKey.get(descriptionKey);

                            long assetID = 0;
                            if (item.hasAttr("href")) {
                                assetID = Long.parseLong(item.attr("href").split("inventory/#730_2_")[1]);
                            }

                            Item parsedItem = new Item();
                            parsedItem.setDescription(description);
                            parsedItem.setDescriptionKey(descriptionKey);
                            parsedItem.setAssetID(assetID);
                            parsedItems.add(parsedItem);


                        }
                        if (incoming) {
                            inventoryChangeEntry.setItemsAdded(parsedItems);
                        } else {
                            inventoryChangeEntry.setItemsRemoved(parsedItems);
                        }
                    }

                    if (inventoryChangeEntries.contains(inventoryChangeEntry)) {
                        System.out.println("inventoryChangeEntry already present skipping.");
                    } else {
                        inventoryChangeEntries.add(inventoryChangeEntry);

                    }
                }
            } catch (Exception e) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                e.printStackTrace(pw);
                logToConsoleAndFile(sw.toString());
                logToConsoleAndFile("Dump with name " + f.getName() + " seems to be corrupted");
            }

        }

        inventoryChangeEntries.sort(Comparator.comparingLong(InventoryChangeEntry::getTime));

        for (int i = 0; i < inventoryChangeEntries.size(); i++) {
            InventoryChangeEntry inventoryChangeEntry = inventoryChangeEntries.get(i);
            logToConsoleAndFile(Instant.ofEpochSecond(inventoryChangeEntry.getTime()).toString());
            logToConsoleAndFile("Event:" + inventoryChangeEntry.getEvent());
            logToConsoleAndFile("+");

            inventoryChangeEntry.getItemsAdded().forEach(item -> {
                logToConsoleAndFile(item.getDescription().get("market_hash_name").getAsString());
            });

            logToConsoleAndFile("-");

            inventoryChangeEntry.getItemsRemoved().forEach(item -> {
                logToConsoleAndFile(item.getDescription().get("market_hash_name").getAsString());
            });

        }

        return inventoryChangeEntries;
    }

    public void analyseUnboxings(File path) {
        var entries = parseFullHistory(path);
        List<InventoryChangeEntry> unboxEntries = getEntriesForType(entries, "Unlocked a container");
        var unboxTypes = new ArrayList<>(getAllAvailableUnboxingTypes(unboxEntries).entrySet());
        var selectedUnboxType = "";
        logToConsoleAndFile("Found " + unboxTypes.size() + " unboxing types");
        logToConsoleAndFile("Please select one unbox type");
        for (int i = 0; i < unboxTypes.size(); i++) {
            logToConsoleAndFile((i + 1) + ") -> " + unboxTypes.get(i).getKey() + "(" + unboxTypes.get(i).getValue() + ")");
        }
        Scanner scanner = new Scanner(System.in);
        var input = Integer.parseInt(scanner.nextLine()) - 1;
        selectedUnboxType = unboxTypes.get(input).getKey();
        var unboxedRarities = new HashMap<Rarity, Integer>();
        var filtered = getEventsFilteredByUnboxType(unboxEntries, selectedUnboxType);
        filtered.forEach(entry -> {
            var consumedItem = entry.getItemsRemoved().stream().filter(item -> {
                var itemType = item.getDescription().get("type").getAsString();
                return !itemType.equals("Base Grade Key");
            }).collect(Collectors.toList());
            if (consumedItem.size() == 1 && entry.getItemsAdded().size() == 1) {
                var rarity = Rarity.fromDescription(entry.getItemsAdded().get(0).getDescription());
                logToConsoleAndFile(consumedItem.get(0).getDescription().get("market_hash_name").getAsString() + "->" + entry.getItemsAdded().get(0).getDescription().get("market_hash_name").getAsString() + " (" + rarity + ")");
                unboxedRarities.put(rarity, unboxedRarities.getOrDefault(rarity, 0) + 1);
            } else {
                logToConsoleAndFile("Item amount not matching");
                logToConsoleAndFile(entry.toString());
            }
        });
        var totalUnboxed = unboxedRarities.values().stream().mapToDouble(Integer::intValue).sum();
        logToConsoleAndFile("Total " + selectedUnboxType + " unboxed: " + (int) totalUnboxed);

        logToConsoleAndFile("");
        logToConsoleAndFile("Item distribution and odds calculation");
        logToConsoleAndFile("Rarity name | Yours | Official");
        logToConsoleAndFile("-------------------------------------------");

        OddsUtils.getOddsForUnboxType(selectedUnboxType).forEach((rarity, chance) -> {
            double calculatedOdds = round((unboxedRarities.getOrDefault(rarity, 0) / totalUnboxed) * 100, 2);
            String amountAndTotal = unboxedRarities.getOrDefault(rarity, 0) + "/" + (int) totalUnboxed;
            logToConsoleAndFile(format(rarity.toString(), 8, false) + " | " + format(amountAndTotal, 15, true) + " (~" + format(calculatedOdds + "", 6, true) + " %) | " + format(round(chance * 100, 3) + "", 7, true) + "%");
        });

        waitForInputAndContinue();
    }

    public void analyseCaseDrops(File path) {
        var entries = parseFullHistory(path);
        List<InventoryChangeEntry> itemDrops = getEntriesForType(entries, "Got an item drop");
        var allCaseDrops = itemDrops.stream().filter(event -> {
            if (event.getItemsAdded().size() == 1) {
                var drop = event.getItemsAdded().get(0);
                return "1".equals(drop.getDescription().get("commodity").getAsString());
            }
            return false;
        }).collect(Collectors.toList());
        var droppedItems = new HashMap<String, Integer>();

        allCaseDrops.forEach(drop -> {
            var item = drop.getItemsAdded().get(0).getDescription().get("market_hash_name").getAsString();
            droppedItems.put(item, droppedItems.getOrDefault(item, 0) + 1);
            logToConsoleAndFile(item + " time: " + longToStringDateConverter.format(drop.getTime() * 1000));
        });
        var finalItems = droppedItems;
        finalItems = droppedItems.entrySet().stream()
                .sorted(Collections.reverseOrder(Comparator.comparingDouble(Map.Entry::getValue)))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));
        var totalDrops = droppedItems.values().stream().mapToInt(Integer::intValue).sum();
        finalItems.forEach((name, amount) -> {
            logToConsoleAndFile(format(name, 32, false) + " | " + format(String.valueOf(amount), 7, true));
        });
        logToConsoleAndFile("Total. cases: " + totalDrops);

        waitForInputAndContinue();
    }

    private List<InventoryChangeEntry> getEntriesForType(List<InventoryChangeEntry> entries, String eventName) {
        var events = getAllEventTypesExcludingTrades(entries);
        if (!events.contains(eventName)) {
            logToConsoleAndFile("Found " + events.size() + " event names");
            logToConsoleAndFile("Please select" + eventName + " in your language");
            for (int i = 0; i < events.size(); i++) {
                logToConsoleAndFile(i + ") -> " + events.get(i));
            }
            Scanner scanner = new Scanner(System.in);
            var input = Integer.valueOf(scanner.nextLine());
            eventName = events.get(input);
        }
        String finalEventName = eventName;
        return entries.stream().filter(entry -> finalEventName.equals(entry.getEvent())).collect(Collectors.toList());
    }


    private String getMainUser(List<InventoryChangeEntry> entries) {
        Map<String, Long> counts =
                entries.stream().filter(e -> e.getPartner() != null).collect(Collectors.groupingBy(e -> e.getPartner().split("/inventory/#")[0], Collectors.counting()));
        var detectedUser = counts.entrySet().stream().max(Comparator.comparingLong(value -> value.getValue())).stream().findFirst().get().getKey();
        logToConsoleAndFile("Detected user: " + detectedUser);
        return detectedUser;
    }

    private List<String> getAllEventTypesExcludingTrades(List<InventoryChangeEntry> entries) {
        List<String> events = new ArrayList<>();
        var mainUser = getMainUser(entries);
        entries.forEach(entry -> {
            if (entry.getPartner() != null && !entry.getPartner().contains(mainUser)) {
                return;
            }
            if (!events.contains(entry.getEvent())) {
                events.add(entry.getEvent());
            }
        });
        return events;
    }

    private HashMap<String, Integer> getAllAvailableUnboxingTypes(List<InventoryChangeEntry> entries) {
        HashMap<String, String> marketNameToUnboxType = getMarketNameToUnboxTypeMap();
        HashMap<String, Integer> types = new HashMap<>();
        entries.forEach(entry -> {
            entry.getItemsRemoved().forEach(item -> {
                var itemType = item.getDescription().get("type").getAsString();
                if (itemType.equals("Base Grade Key")) {
                    return;
                }
                var name = item.getDescription().get("market_hash_name").getAsString();
                var type = marketNameToUnboxType.get(name);
                if (type == null) {
                    logToConsoleAndFile("Couldnt find unboxing type from:" + name);
                    return;
                }
                types.put(type, types.getOrDefault(type, 0) + 1);

            });
        });
        return types;
    }

    private List<InventoryChangeEntry> getEventsFilteredByUnboxType(List<InventoryChangeEntry> unboxEntries, String unboxType) {
        HashMap<String, String> marketNameToUnboxType = getMarketNameToUnboxTypeMap();
        return unboxEntries.stream().filter(entry -> entry.getItemsRemoved().stream().anyMatch(item -> marketNameToUnboxType.getOrDefault(item.getDescription().get("market_hash_name").getAsString(), "").equals(unboxType))).collect(Collectors.toList());
    }

    private static HashMap<String, String> getMarketNameToUnboxTypeMap() {
        HashMap<String, String> marketNameToUnboxType = new HashMap<>();
        try {
            Files.readAllLines(Path.of("marketNames.txt")).forEach(line -> {
                var split = line.split(";");
                marketNameToUnboxType.put(split[0], split[1]);
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return marketNameToUnboxType;
    }

    private void waitForInputAndContinue() {

        try {

            if (outputStream != null) {
                outputStream.flush();
                outputStream.close();
            }

            System.out.println();
            System.out.println("Press ENTER to continue...");
            System.in.read();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void logToConsoleAndFile(String msg) {
        logToConsoleAndFile(msg, false);
    }

    private void logToConsoleAndFile(String msg, boolean newFile) {
        System.out.println(msg);
        try {
            getResultOutputStream(newFile).write((msg + System.lineSeparator()).getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private OutputStream getResultOutputStream(boolean newFile) throws IOException {

        if (resultFile == null || newFile) {
            SimpleDateFormat sdf = new SimpleDateFormat("dd_MM_yyyy_HH_mm_ss");
            Date dt = new Date();
            String date = sdf.format(dt);

            resultFile = new File("result_" + date + ".txt");
            if (!resultFile.exists()) {
                resultFile.createNewFile();
            }

            outputStream = new FileOutputStream(resultFile);
        }

        return outputStream;
    }

    public static AnalysisService getInstance() {
        if (instance == null) {
            instance = new AnalysisService();
        }
        return instance;
    }
}
