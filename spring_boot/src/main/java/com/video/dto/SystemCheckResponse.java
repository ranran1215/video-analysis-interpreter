package com.video.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SystemCheckResponse {
    private boolean ok;
    private List<CheckItem> checks = new ArrayList<>();

    public void addCheck(String name, boolean ok, String message) {
        CheckItem item = new CheckItem();
        item.setName(name);
        item.setOk(ok);
        item.setMessage(message);
        checks.add(item);
        this.ok = checks.stream().allMatch(CheckItem::isOk);
    }

    @Data
    public static class CheckItem {
        private String name;
        private boolean ok;
        private String message;
    }
}
