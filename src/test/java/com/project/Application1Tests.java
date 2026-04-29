package com.project;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class Application1Tests {

    @Test
    void contextLoads() {
        String a = "a";
        if(a.equals("a") && a instanceof Object){
            System.out.println(a.hashCode());
            Object b = new Object();
            System.out.println(b.hashCode());
        }
    }



}
