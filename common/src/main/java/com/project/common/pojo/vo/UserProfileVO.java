package com.project.common.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UserProfileVO implements Serializable {

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private String username;
    private String phone;
    private String realName;
    private String idCard;
    private LocalDateTime createTime;
}
