package com.project.common.pojo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PassengerSaveDTO implements Serializable {

    private String realName;
    private String idCard;
    private String passengerType;  // ADULT / STUDENT / CHILD
}
