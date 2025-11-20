package com.example.kpo.service;

import com.example.kpo.entity.Admin;
import com.example.kpo.repository.AdminRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AdminService{
    private AdminRepository adminRepository;
    private PasswordEncoder passwordEncoder;

    public AdminService(AdminRepository adminRepository, PasswordEncoder passwordEncoder) {
        this.adminRepository = adminRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<Admin> getAllAdmins() {
        return adminRepository.findAll();
    }

    public void createAdminIfEmptuDB(){
        if (adminRepository.count() == 0){
            adminRepository.save(new Admin(null,"ADMIN",passwordEncoder.encode("")));
        }
    }
}
