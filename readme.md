# Online-Beratung UserService

The UserService owns user and consultant accounts, enquiries, counselling
sessions, session lists, and the application-side lifecycle of Matrix rooms.

It most importantly covers the lifecycle of a consultation/session:
  - registration of new users/askers
  - handling and creation of enquiries
  - creation of associated sessions and encrypted Matrix rooms
  - assignment of consultants and Matrix room memberships

Furthermore it handles the different kinds of consultations:
  - single/direct 1:1 counseling
  - team counseling
  - group chat counseling
  - anonymous counseling (no registration requried)

In addition to that it provides different lists of sessions for consultants and askers:
  - asker sessions
  - enquiries/anonymous enquiries
  - sessions directly assigned to consultant
  - team sessions
  - group chats

Moreover it also offers different workflows for deactivating expired group chats, deactivating old anonymous user accounts and deleting user accounts.
On top of that the UserService includes useful admin API calls to administrate user accounts.

## Help and Documentation
In the project [documentation](https://onlineberatung.github.io/documentation/docs/setup/setup-backend) you'll find information for setting up and running the project.
You can find some detailled information of the service architecture and its processes in the repository [documentation](https://github.com/Onlineberatung/onlineBeratung-userService/tree/master/documentation).

## License
The project is licensed under the AGPLv3 which you'll find [here](https://github.com/Onlineberatung/onlineBeratung-userService/blob/master/LICENSE).

## Code of Conduct
Please have a look at our [Code of Conduct](https://github.com/Onlineberatung/.github/blob/master/CODE_OF_CONDUCT.md) before participating in the community.

## Contributing
Please read our [contribution guidelines](https://github.com/Onlineberatung/.github/blob/master/CONTRIBUTING.md) before contributing to this project.
