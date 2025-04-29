# PopularPicks - Local Lifestyle Services Platform  

*A demo project for local lifestyle services based on SpringBoot + Redis + MySQL, featuring merchant search, flash sales, and location-based recommendations.*

## 📌 Project Overview  
Simulates core features of DianPing (China's Yelp equivalent), implementing high-concurrency solutions for multi-scenario cases. Ideal for learning distributed middleware and performance optimization.

## 🛠️ Tech Stack  
| Category       | Technologies                                                                 |
|----------------|-----------------------------------------------------------------------------|
| **Core**       | SpringBoot 2.7 + MyBatis-Plus + SpringMVC                                   |
| **Database**   | MySQL 8.0 + Redis 6.2 (Cache/Distributed Lock/Flash Sales)                  |
| **Middleware** | RabbitMQ (Async Orders) + GeoHash (Nearby Shops) + JWT (Stateless Auth)     |
| **Monitoring** | Prometheus + Grafana                                                        |
| **Utilities**  | Lombok + Hutool + Swagger 3.0 (API Docs)                                    |

## 🌟 Core Features  
### 1. Merchant Services  
- Nearby shop recommendations (Geo-spatial calculations)  
- Cache penetration solutions for merchant details  
- Multi-level category query optimization  

### 2. Coupon Flash Sales  
```java
// Distributed lock implementation example
public Result seckillVoucher(Long voucherId) {
    // 1. Redis atomic inventory check
    // 2. Distributed lock to prevent overselling
    // 3. MQ async order creation
}
